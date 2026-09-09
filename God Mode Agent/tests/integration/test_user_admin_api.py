"""User/role management + API-key lifecycle endpoints (Settings control center phase 1)."""

from __future__ import annotations


def _register(client, username, password, role="user"):
    resp = client.post("/auth/register", json={"username": username, "password": password, "role": role})
    assert resp.status_code == 200, resp.text
    return resp.json()["data"]


# ---------- /users listing ----------

def test_admin_lists_users_including_seeded_admin(client):
    resp = client.get("/users")
    assert resp.status_code == 200
    users = resp.json()["data"]["users"]
    assert any(u["username"] == "admin" and u["role"] == "admin" for u in users)
    assert all("password_hash" not in u for u in users)


def test_user_listing_requires_admin_role(operator_client):
    resp = operator_client.get("/users")
    assert resp.status_code == 403


# ---------- self-service password change ----------

def test_change_password_rejects_wrong_current_password(app):
    from fastapi.testclient import TestClient

    with TestClient(app) as c:
        admin = c.post("/auth/login", json={"username": "admin", "password": "admin123"}).json()["data"]
        c.headers.update({"Authorization": f"Bearer {admin['access_token']}"})
        _register(c, "pwuser", "first-pass-123")
        tokens = c.post("/auth/login", json={"username": "pwuser", "password": "first-pass-123"}).json()["data"]

        resp = c.post("/auth/change-password",
                      json={"current_password": "WRONG", "new_password": "second-pass-456"},
                      headers={"Authorization": f"Bearer {tokens['access_token']}"})
        assert resp.status_code == 401


def test_change_password_rotates_and_invalidates_old_login(app):
    from fastapi.testclient import TestClient

    with TestClient(app) as c:
        admin = c.post("/auth/login", json={"username": "admin", "password": "admin123"}).json()["data"]
        c.headers.update({"Authorization": f"Bearer {admin['access_token']}"})
        _register(c, "pwuser2", "first-pass-123")
        tokens = c.post("/auth/login", json={"username": "pwuser2", "password": "first-pass-123"}).json()["data"]

        resp = c.post("/auth/change-password",
                      json={"current_password": "first-pass-123", "new_password": "second-pass-456"},
                      headers={"Authorization": f"Bearer {tokens['access_token']}"})
        assert resp.status_code == 200

        assert c.post("/auth/login", json={"username": "pwuser2", "password": "first-pass-123"}).status_code == 401
        assert c.post("/auth/login", json={"username": "pwuser2", "password": "second-pass-456"}).status_code == 200


# ---------- admin password reset ----------

def test_admin_reset_returns_working_temp_password(client):
    created = _register(client, "resetme", "orig-pass-123")

    resp = client.post(f"/users/{created['id']}/reset-password")
    assert resp.status_code == 200
    temp = resp.json()["data"]["temporary_password"]
    assert temp.startswith("tmp-")

    assert client.post("/auth/login", json={"username": "resetme", "password": "orig-pass-123"}).status_code == 401
    assert client.post("/auth/login", json={"username": "resetme", "password": temp}).status_code == 200


# ---------- role management ----------

def test_role_change_takes_effect_on_next_login(client):
    created = _register(client, "promote_me", "promo-pass-123")

    resp = client.post(f"/users/{created['id']}/role", json={"role": "operator"})
    assert resp.status_code == 200
    assert resp.json()["data"]["role"] == "operator"

    tokens = client.post("/auth/login", json={"username": "promote_me", "password": "promo-pass-123"}).json()["data"]
    assert tokens["user"]["role"] == "operator"


def test_invalid_role_is_rejected(client):
    created = _register(client, "badrole", "role-pass-123")
    resp = client.post(f"/users/{created['id']}/role", json={"role": "superuser"})
    assert resp.status_code == 400
    assert resp.json()["error"]["code"] == "INVALID_ROLE"


def test_last_active_admin_cannot_be_demoted(client):
    admin_id = next(u["id"] for u in client.get("/users").json()["data"]["users"] if u["username"] == "admin")
    resp = client.post(f"/users/{admin_id}/role", json={"role": "user"})
    assert resp.status_code == 401  # AuthError from the last-admin guard
    assert "last active admin" in resp.json()["error"]["message"]


# ---------- disable / enable ----------

def test_disabled_user_cannot_login_until_reenabled(client):
    created = _register(client, "toggler", "toggle-pass-123")

    assert client.post(f"/users/{created['id']}/disable").status_code == 200
    assert client.post("/auth/login", json={"username": "toggler", "password": "toggle-pass-123"}).status_code == 401

    assert client.post(f"/users/{created['id']}/enable").status_code == 200
    assert client.post("/auth/login", json={"username": "toggler", "password": "toggle-pass-123"}).status_code == 200


def test_admin_cannot_disable_own_account(client):
    admin_id = next(u["id"] for u in client.get("/users").json()["data"]["users"] if u["username"] == "admin")
    resp = client.post(f"/users/{admin_id}/disable")
    assert resp.status_code == 403
    assert resp.json()["error"]["code"] == "SELF_DISABLE_FORBIDDEN"


# ---------- API keys ----------

def test_api_key_lifecycle_create_list_revoke(client):
    created = client.post("/apikeys", json={"name": "ci-bot", "role": "user"}).json()["data"]
    assert created["api_key"].startswith("jk_")

    keys = client.get("/apikeys").json()["data"]["keys"]
    mine = next(k for k in keys if k["id"] == created["id"])
    assert mine["name"] == "ci-bot" and mine["revoked"] is False
    assert "key_hash" not in mine and "api_key" not in mine

    assert client.post(f"/apikeys/{created['id']}/revoke").status_code == 200
    keys = client.get("/apikeys").json()["data"]["keys"]
    assert next(k for k in keys if k["id"] == created["id"])["revoked"] is True


def test_api_key_listing_requires_admin(operator_client):
    assert operator_client.get("/apikeys").status_code == 403
    assert operator_client.post("/apikeys", json={"name": "x"}).status_code == 403
