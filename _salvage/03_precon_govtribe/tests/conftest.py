"""Pytest fixtures for the Precon test suite.

The suite runs against an in-memory SQLite database so tests stay fast and
hermetic.  We bring up only the precon ORM tables (the OCERP tables aren't
needed for unit-level coverage of the state machine, leveling engine, prequal
audit log, and heartbeat); cross-module call paths in the service layer
gracefully skip when the OCERP models can't be imported.
"""

import asyncio
import os
import uuid
from collections.abc import AsyncIterator

import pytest
import pytest_asyncio
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

# ── Test environment bootstrap ────────────────────────────────────────────
#
# The precon module lives at ``app/modules/precon/`` inside the OCERP tree
# (after Phase 7.1 integration), so ``app.modules.precon`` resolves
# naturally — no sys.path tricks needed.  The remaining bootstrap is
# purely about isolating tests from the live OCERP database:
#
#   1. Pin ``DATABASE_URL`` to in-memory SQLite *before* ``app.config``
#      loads so OCERP's engine gets built against the test database.
#   2. Strip ``pool_size`` / ``max_overflow`` from ``create_async_engine``
#      kwargs when the URL is SQLite — OCERP's database module passes
#      them unconditionally and StaticPool rejects them.
#
# Both shims are no-ops in production; they only fire under pytest.

os.environ.setdefault(
    "DATABASE_URL",
    "sqlite+aiosqlite:///file:precon_test?mode=memory&cache=shared&uri=true",
)
os.environ.setdefault("ENV", "test")


# ── Pytest marker registration ────────────────────────────────────────────


def pytest_configure(config):  # noqa: ANN001 — pytest hook
    """Register custom markers so unrecognised-marker warnings stay quiet."""
    config.addinivalue_line(
        "markers",
        "integration: opt-in tests that require the live OCERP stack or external services",
    )


# ── SQLAlchemy SQLite shim ────────────────────────────────────────────────
#
# OCERP's ``app.database`` constructs the engine at module import time and
# unconditionally passes ``pool_size`` / ``max_overflow`` — both unsupported
# on the SQLite + StaticPool combination we use for tests.  Wrap the
# SQLAlchemy factory so SQLite URLs silently drop those kwargs.  The wrap
# is installed *before* OCERP's database module loads.
import sqlalchemy.ext.asyncio as _sa_async  # noqa: E402

_orig_create_async_engine = _sa_async.create_async_engine


def _safe_create_async_engine(url, **kwargs):
    if "sqlite" in str(url):
        kwargs.pop("pool_size", None)
        kwargs.pop("max_overflow", None)
    return _orig_create_async_engine(url, **kwargs)


_sa_async.create_async_engine = _safe_create_async_engine


# ── Event loop scope ──────────────────────────────────────────────────────


@pytest.fixture(scope="session")
def event_loop():  # type: ignore[no-untyped-def]
    """Single event loop shared across the session."""
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()


# ── Database engine + schema ──────────────────────────────────────────────


@pytest_asyncio.fixture(scope="session")
async def engine():
    """Session-scoped async engine bound to a shared in-memory SQLite database.

    OCERP's ``app.database`` installs a global ``connect`` event listener that
    flips ``PRAGMA foreign_keys=ON`` on every SQLite connection.  That's the
    correct behaviour for the production database — it enforces every FK we
    declared — but tests use synthetic UUIDs that don't reference real
    parent rows, so we re-disable the pragma after OCERP's listener fires
    by binding our own listener that runs last.
    """
    from sqlalchemy import event as sa_event
    from sqlalchemy.engine import Engine as _SyncEngine

    @sa_event.listens_for(_SyncEngine, "connect")
    def _disable_fks_for_tests(dbapi_conn, _conn_record):  # noqa: ANN001 — sa_event sig
        cursor = dbapi_conn.cursor()
        try:
            cursor.execute("PRAGMA foreign_keys=OFF")
        finally:
            cursor.close()

    eng = create_async_engine(
        "sqlite+aiosqlite:///file:precon_test?mode=memory&cache=shared&uri=true",
        echo=False,
        future=True,
    )
    yield eng
    await eng.dispose()


@pytest_asyncio.fixture(scope="session")
async def _schema(engine):
    """Create the precon ORM tables (and the FK-referenced OCERP tables).

    The precon models declare FKs to ``oe_users_user``, ``oe_projects_project``,
    ``oe_contacts_contact``, ``oe_tendering_package``, and ``oe_rfq_rfq``.
    SQLAlchemy's CREATE TABLE dependency sort needs those tables registered
    in the same MetaData, so we import the OCERP model modules first.  This
    is purely a *test-time* setup — the precon module itself never imports
    these.
    """
    from app.database import Base  # type: ignore[import-not-found]

    # Force-load OCERP models so their tables register on Base.metadata.
    # Each import is wrapped because some OCERP modules carry heavy deps
    # that may not import cleanly outside the full stack; we only need the
    # five tables our FKs reference.
    for module_path in (
        "app.modules.users.models",
        "app.modules.projects.models",
        "app.modules.contacts.models",
        "app.modules.tendering.models",
        "app.modules.rfq_bidding.models",
        # Teams is needed because OCERP's ProjectService.create_project
        # auto-creates a Default Team for every new project; without the
        # table the session enters PendingRollbackError before the adapter
        # can return.
        "app.modules.teams.models",
    ):
        try:
            __import__(module_path)
        except Exception as exc:  # pragma: no cover — best-effort
            print(f"WARN: skipping OCERP model import {module_path}: {exc}")

    # Now load the precon models.
    from app.modules.precon import models as _precon_models  # noqa: F401

    async with engine.begin() as conn:
        await conn.run_sync(lambda sync_conn: Base.metadata.create_all(sync_conn))
    yield
    async with engine.begin() as conn:
        await conn.run_sync(lambda sync_conn: Base.metadata.drop_all(sync_conn))


@pytest_asyncio.fixture
async def session(engine, _schema) -> AsyncIterator[AsyncSession]:
    """Per-test async session.

    The schema is session-scoped (faster), but tests routinely call
    ``session.commit()`` to make data visible to a second session opened by
    e.g. the heartbeat task.  That breaks isolation between tests, so we
    truncate every precon table at teardown — cheap on SQLite and keeps
    each test starting from an empty cache.
    """
    factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    async with factory() as session:
        try:
            yield session
        finally:
            await session.rollback()
    # Wipe any committed rows so the next test starts clean.
    from sqlalchemy import text as sa_text

    async with engine.begin() as conn:
        for table in (
            "oe_nexus_precon_stage_event",
            "oe_nexus_precon_bid_level",
            "oe_nexus_precon_opportunity_cache",
            "oe_nexus_precon_prequalification_event",
            "oe_nexus_precon_rfq_invitation",
        ):
            await conn.execute(sa_text(f"DELETE FROM {table}"))


# ── Lightweight ID helpers ────────────────────────────────────────────────


@pytest.fixture
def project_id() -> uuid.UUID:
    return uuid.uuid4()


@pytest.fixture
def contact_id() -> uuid.UUID:
    return uuid.uuid4()


@pytest.fixture
def user_id() -> uuid.UUID:
    return uuid.uuid4()


@pytest.fixture
def package_id() -> uuid.UUID:
    return uuid.uuid4()


@pytest.fixture
def rfq_id() -> uuid.UUID:
    return uuid.uuid4()


# ── Project state stub ────────────────────────────────────────────────────
#
# The pipeline service expects to read/write ``oe_projects_project.phase``.
# Without OCERP tables present we monkeypatch the loader so the state machine
# can be exercised against an in-memory dict keyed by project_id.  Tests that
# need the full OCERP stack should be skipped (we mark them ``integration``).


@pytest.fixture
def project_phase_store(monkeypatch):
    """In-memory replacement for ``ProjectService`` reads / writes."""
    from app.modules.precon.services import pipeline_service as ps

    store: dict[uuid.UUID, str | None] = {}

    async def _load_project(self, project_id):  # noqa: ARG001
        stub = type("ProjectStub", (), {})()
        stub.id = project_id
        stub.phase = store.get(project_id)
        return stub

    async def _set_project_phase(self, project_id, phase):  # noqa: ARG001
        store[project_id] = phase

    monkeypatch.setattr(ps.PipelineService, "_load_project", _load_project)
    monkeypatch.setattr(ps.PipelineService, "_set_project_phase", _set_project_phase)
    return store
