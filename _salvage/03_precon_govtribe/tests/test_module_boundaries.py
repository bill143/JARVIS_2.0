"""Suite 9 — module boundary enforcement (T09-01 .. T09-14).

These are static scans over the precon source tree, asserting the locked
"no shadow tables / no encroachment" rules from the build contract.  They
catch accidental coupling at PR review time.

Some tests in the spec (T09-06 .. T09-14) are end-to-end integration tests
that require the full OCERP stack running in Docker; we shadow them here as
``pytest.mark.skip`` placeholders so the test names match the spec one-for-one.
"""

import re
from pathlib import Path

import pytest

PRECON_ROOT = Path(__file__).resolve().parent.parent  # …/modules/precon
SOURCE_FILES = sorted(p for p in PRECON_ROOT.rglob("*.py") if "tests" not in p.parts)


# ── T09-01: zero direct ORM access to other modules' tables ──────────────


@pytest.mark.parametrize("forbidden_prefix", [
    "oe_tendering_",
    "oe_rfq_",
    "oe_finance_",
    "oe_documents_",
    "oe_procurement_",
    "oe_contacts_",
])
def test_no_direct_table_string_for(forbidden_prefix):
    """No ``__tablename__ = "oe_<other>_*"`` and no raw text() with that prefix.

    The precon source files MAY mention these table names in comments and
    docstrings.  What they must not do is declare a model against them or
    issue a raw ``text("SELECT ... FROM oe_<other>_*")``.
    """
    declaration_pattern = re.compile(
        rf'__tablename__\s*=\s*[\'"]{re.escape(forbidden_prefix)}',
    )
    raw_text_pattern = re.compile(
        rf'text\([^)]*{re.escape(forbidden_prefix)}',
    )
    offenders: list[str] = []
    for path in SOURCE_FILES:
        text = path.read_text(encoding="utf-8")
        if declaration_pattern.search(text) or raw_text_pattern.search(text):
            offenders.append(str(path))
    assert offenders == [], (
        f"Files declare or hand-craft SQL against {forbidden_prefix!r}: {offenders}"
    )


# ── T09-02: no imports from other modules' repository.py ─────────────────


def test_no_cross_module_repository_imports():
    pattern = re.compile(r"from\s+app\.modules\.([a-z_]+)\.repository\s+import")
    offenders: list[tuple[str, str]] = []
    for path in SOURCE_FILES:
        text = path.read_text(encoding="utf-8")
        for match in pattern.finditer(text):
            module = match.group(1)
            if module != "precon":
                offenders.append((str(path), module))
    assert offenders == [], (
        "Precon must not import other modules' repository.py: " + repr(offenders)
    )


# ── T09-03: only oe_nexus_precon_* tables declared ───────────────────────


def test_only_precon_tables_declared():
    pattern = re.compile(r'__tablename__\s*=\s*[\'"]([^\'"]+)[\'"]')
    declared: set[str] = set()
    for path in SOURCE_FILES:
        text = path.read_text(encoding="utf-8")
        for match in pattern.finditer(text):
            declared.add(match.group(1))
    bad = {t for t in declared if not t.startswith("oe_nexus_precon_")}
    assert bad == set(), f"Precon declares non-precon tables: {bad}"


# ── T09-04: precon defines no APIRouters that mount under non-/api/precon ─


def test_router_prefix_is_api_precon():
    """Verify the precon router mounts under /api/precon either via the
    APIRouter constructor or the OCERP main.py include_router call.

    Phase 7.1 moved the prefix to main.py to mirror OCERP's convention
    (e.g. ``app.include_router(i18n_router, prefix="/api/v1")``).  This
    test passes when the prefix lives in EITHER place — it's the
    end-to-end mount path that matters.
    """
    router_path = PRECON_ROOT / "router.py"
    router_text = router_path.read_text(encoding="utf-8")
    api_router_matches = re.findall(
        r'APIRouter\([^)]*prefix\s*=\s*[\'"]([^\'"]+)[\'"]',
        router_text,
    )
    if api_router_matches:
        for prefix in api_router_matches:
            assert prefix.startswith("/api/precon"), (
                f"Router prefix {prefix!r} does not live under /api/precon"
            )
        return

    # Fall through: the APIRouter has no prefix, so the prefix must be
    # supplied by the OCERP main.py include_router call.
    main_py = PRECON_ROOT.parent.parent / "main.py"
    if not main_py.exists():
        # Pre-integration test rig — no main.py yet.  Nothing to check.
        return
    main_text = main_py.read_text(encoding="utf-8")
    assert (
        "from app.modules.precon.router import" in main_text
        or "from app.modules.precon import router" in main_text
    ), "main.py does not import the precon router"
    include_matches = re.findall(
        r'include_router\([^)]*precon_router[^)]*prefix\s*=\s*[\'"]([^\'"]+)[\'"]',
        main_text,
    )
    assert include_matches, (
        "Neither APIRouter() nor include_router() declares the /api/precon prefix"
    )
    for prefix in include_matches:
        assert prefix.startswith("/api/precon"), (
            f"include_router prefix {prefix!r} does not live under /api/precon"
        )


# ── T09-05: ORM model files outside precon/models.py declare nothing ──────


def test_only_models_module_declares_orm_classes():
    pattern = re.compile(r'__tablename__\s*=\s*[\'"]')
    offenders: list[str] = []
    for path in SOURCE_FILES:
        if path.name == "models.py":
            continue
        text = path.read_text(encoding="utf-8")
        if pattern.search(text):
            offenders.append(str(path))
    assert offenders == [], (
        "ORM table declarations found outside models.py: " + repr(offenders)
    )


# ── T09-06 .. T09-14: full-stack regression — skipped here ────────────────


@pytest.mark.skip(reason="T09-06 — requires real OCERP stack")
def test_existing_project_crud_unchanged():
    pass


@pytest.mark.skip(reason="T09-07 — Estimating module e2e")
def test_estimating_loads_for_existing_project():
    pass


@pytest.mark.skip(reason="T09-08 — Scheduling module e2e")
def test_scheduling_loads_for_existing_project():
    pass


@pytest.mark.skip(reason="T09-09 — Finance module e2e")
def test_finance_loads_for_existing_project():
    pass


@pytest.mark.skip(reason="T09-10 — Documents module e2e")
def test_documents_loads_for_existing_project():
    pass


@pytest.mark.skip(reason="T09-11 — Procurement module e2e")
def test_procurement_loads_for_existing_project():
    pass


@pytest.mark.skip(reason="T09-12 — Schedule on Precon project e2e")
def test_schedule_loads_for_precon_project():
    pass


@pytest.mark.skip(reason="T09-13 — Tendering page e2e")
def test_tendering_page_renders_with_new_column():
    pass


@pytest.mark.skip(reason="T09-14 — Contacts page e2e")
def test_contacts_page_renders_with_new_flags():
    pass
