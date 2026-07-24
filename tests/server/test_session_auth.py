"""Tests for the session-auth gateway (Stage 1: production hardening)."""

from __future__ import annotations

import pytest

pytest.importorskip("fastapi", reason="openjarvis[server] not installed")

from fastapi import FastAPI  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from openjarvis.server.auth import (  # noqa: E402
    AuthStore,
    hash_password,
    verify_password,
)
from openjarvis.server.session_auth import (  # noqa: E402
    SessionAuthMiddleware,
    create_auth_router,
)

# ---------------------------------------------------------------------------
# AuthStore + password hashing (unit)
# ---------------------------------------------------------------------------


def test_password_hashing_roundtrip():
    h = hash_password("s3cret-pw")
    assert h != "s3cret-pw"  # never plaintext
    assert verify_password("s3cret-pw", h)
    assert not verify_password("wrong", h)
    assert not verify_password("s3cret-pw", "not-a-hash")


def test_user_create_verify_and_no_duplicate():
    store = AuthStore(":memory:")
    assert store.create_user("bill", "hunter2")
    assert not store.create_user("bill", "other")  # duplicate rejected
    assert store.verify_user("bill", "hunter2")
    assert not store.verify_user("bill", "nope")
    assert not store.verify_user("ghost", "x")  # unknown user


def test_ensure_admin_only_on_empty_store():
    store = AuthStore(":memory:")
    assert store.ensure_admin("admin", "pw")  # created
    assert not store.ensure_admin("admin2", "pw2")  # store not empty → no-op
    assert store.user_count() == 1
    assert store.verify_user("admin", "pw")


def test_session_lifecycle_and_expiry():
    store = AuthStore(":memory:")
    store.create_user("bill", "pw")
    token = store.create_session("bill", ttl_hours=1)
    assert store.validate_session(token) == "bill"
    assert store.validate_session("bogus") is None
    assert store.validate_session(None) is None
    # Expired session is rejected and cleared.
    expired = store.create_session("bill", ttl_hours=-1)
    assert store.validate_session(expired) is None
    store.delete_session(token)
    assert store.validate_session(token) is None


# ---------------------------------------------------------------------------
# Middleware + login gateway (integration, fail-closed)
# ---------------------------------------------------------------------------


def _protected_app() -> tuple[TestClient, AuthStore]:
    store = AuthStore(":memory:")
    store.create_user("bill", "hunter2")
    app = FastAPI()
    app.include_router(create_auth_router(store, cookie_secure=False))
    app.add_middleware(SessionAuthMiddleware, store=store)

    @app.get("/dashboard")
    async def dashboard():
        return {"page": "dashboard"}

    @app.get("/v1/models")
    async def models():
        return {"models": []}

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    return TestClient(app, follow_redirects=False), store


def test_unauthenticated_html_redirects_to_login():
    client, _ = _protected_app()
    resp = client.get("/dashboard", headers={"accept": "text/html"})
    assert resp.status_code == 303
    assert resp.headers["location"] == "/login"


def test_unauthenticated_api_gets_401():
    client, _ = _protected_app()
    resp = client.get("/v1/models", headers={"accept": "application/json"})
    assert resp.status_code == 401
    assert "authentication" in resp.json()["detail"].lower()


def test_health_and_login_are_exempt():
    client, _ = _protected_app()
    assert client.get("/health").status_code == 200
    assert client.get("/login").status_code == 200  # login page renders


def test_login_wrong_password_is_rejected():
    client, _ = _protected_app()
    resp = client.post("/login", data={"username": "bill", "password": "wrong"})
    assert resp.status_code == 401
    # No cookie is set on failure.
    assert "set-cookie" not in {k.lower() for k in resp.headers}


def test_full_login_then_access_then_logout_flow():
    client, _ = _protected_app()
    # Log in → session cookie set, redirect to /.
    resp = client.post("/login", data={"username": "bill", "password": "hunter2"})
    assert resp.status_code == 303
    assert resp.headers["location"] == "/"
    assert client.cookies.get("oj_session")

    # Cookie now grants access to protected routes.
    assert client.get("/dashboard").status_code == 200
    assert client.get("/v1/models").status_code == 200

    # Logout clears the session → protected routes fail closed again.
    client.post("/logout")
    assert (
        client.get("/v1/models", headers={"accept": "application/json"}).status_code
        == 401
    )


def test_cookie_is_httponly():
    client, _ = _protected_app()
    resp = client.post("/login", data={"username": "bill", "password": "hunter2"})
    set_cookie = resp.headers.get("set-cookie", "")
    assert "httponly" in set_cookie.lower()
    assert "samesite=lax" in set_cookie.lower()
