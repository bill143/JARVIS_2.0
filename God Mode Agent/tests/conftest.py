"""Test bootstrap: sys.path wiring for the monorepo + shared fixtures."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
_PACKAGE_DIRS = [
    "packages/agent-core", "packages/model-adapters", "packages/vision",
    "packages/voice", "packages/memory", "packages/tools", "packages/shared",
    "packages/auth", "packages/safety", "packages/policy",
    "packages/reliability", "packages/observability",
    "packages/planner", "packages/agents", "packages/rag",
    "packages/routing", "packages/compliance", "packages/evals",
    "apps/api", "apps/desktop-runtime",
]
for rel in _PACKAGE_DIRS:
    p = str(ROOT / rel)
    if p not in sys.path:
        sys.path.insert(0, p)

import pytest  # noqa: E402

from jarvis_shared.config import Settings  # noqa: E402


@pytest.fixture()
def settings(tmp_path) -> Settings:
    """Isolated settings: tmp SQLite, tmp vector dir, tmp workspace, fixed secrets."""
    return Settings(
        _env_file=None,
        openai_api_key="",
        anthropic_api_key="",
        deepgram_api_key="",
        elevenlabs_api_key="",
        database_url=f"sqlite:///{(tmp_path / 'jarvis.db').as_posix()}",
        chroma_persist_dir=str(tmp_path / "chroma"),
        safe_workspace_dir=str(tmp_path / "workspace"),
        rate_limit_rpm=100000,
        rate_limit_per_min=100000,
        sandbox_timeout_seconds=10.0,
        jwt_secret="test-access-secret",
        jwt_refresh_secret="test-refresh-secret",
        allow_dev_auth_bypass=False,
        policy_default_action="deny",
        otel_enabled=False,
        queue_backend="sqlite",
        queue_autostart=False,
        queue_retry_base=0.001,
        queue_max_attempts=2,
        cors_allowed_origins="http://127.0.0.1:3000",
    )


@pytest.fixture()
def app(settings):
    from jarvis_api.main import create_app

    application = create_app(settings)
    yield application


@pytest.fixture()
def raw_client(app):
    """Unauthenticated client (for auth/security tests)."""
    from fastapi.testclient import TestClient

    with TestClient(app) as c:
        yield c


@pytest.fixture()
def admin_tokens(app):
    """The seeded admin's tokens (admin/admin123 created at app startup)."""
    from fastapi.testclient import TestClient

    with TestClient(app) as c:
        resp = c.post("/auth/login", json={"username": "admin", "password": "admin123"})
        assert resp.status_code == 200, resp.text
        yield resp.json()["data"]


@pytest.fixture()
def client(app, admin_tokens):
    """Authenticated (admin) client so Phase 1 behavior tests keep working."""
    from fastapi.testclient import TestClient

    token = admin_tokens["access_token"]
    with TestClient(app) as c:
        c.headers.update({"Authorization": f"Bearer {token}"})
        yield c


def _make_role_client(app, username, password, role):
    """One TestClient: admin registers the role account, then re-auth as that role."""
    from fastapi.testclient import TestClient

    c = TestClient(app)
    c.__enter__()
    admin_login = c.post("/auth/login", json={"username": "admin", "password": "admin123"}).json()["data"]
    c.post("/auth/register", json={"username": username, "password": password, "role": role},
           headers={"Authorization": f"Bearer {admin_login['access_token']}"})
    tokens = c.post("/auth/login", json={"username": username, "password": password}).json()["data"]
    c.headers.update({"Authorization": f"Bearer {tokens['access_token']}"})
    return c


@pytest.fixture()
def operator_client(app):
    """A client authenticated as an 'operator' (can run high-risk tools)."""
    c = _make_role_client(app, "oper", "op-pass-123", "operator")
    try:
        yield c
    finally:
        c.__exit__(None, None, None)


@pytest.fixture()
def readonly_client(app):
    """A client authenticated as a 'readonly' user."""
    c = _make_role_client(app, "read", "ro-pass-123", "readonly")
    try:
        yield c
    finally:
        c.__exit__(None, None, None)
