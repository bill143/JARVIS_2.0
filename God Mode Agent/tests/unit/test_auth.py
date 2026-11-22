"""Auth unit tests: password hashing, JWT, RBAC, refresh rotation, API keys."""

import pytest

from jarvis_auth.jwt_tokens import TokenError, decode, encode
from jarvis_auth.passwords import hash_password, verify_password
from jarvis_auth.rbac import role_at_least
from jarvis_auth.service import AuthService
from jarvis_shared.errors import AuthError, AuthLocked


def test_password_hash_roundtrip():
    h = hash_password("s3cret-pass")
    assert h.startswith("pbkdf2$")
    assert verify_password("s3cret-pass", h)
    assert not verify_password("wrong", h)


def test_jwt_encode_decode_and_tamper():
    token = encode({"sub": "u1", "exp": 9999999999}, "secret")
    assert decode(token, "secret")["sub"] == "u1"
    with pytest.raises(TokenError):
        decode(token, "other-secret")
    with pytest.raises(TokenError):
        decode(token + "x", "secret")


def test_jwt_expiry():
    token = encode({"sub": "u1", "exp": 1}, "secret")  # long expired
    with pytest.raises(TokenError):
        decode(token, "secret")


def test_role_ordering():
    assert role_at_least("admin", "user")
    assert role_at_least("operator", "operator")
    assert not role_at_least("readonly", "user")
    assert not role_at_least("user", "admin")


def test_login_and_refresh_rotation(settings):
    auth = AuthService(settings, settings.sqlite_path)
    auth.register("alice", "password123", role="user")
    tokens = auth.login("alice", "password123")
    assert tokens["access_token"] and tokens["refresh_token"]

    principal = auth.principal_from_access(tokens["access_token"])
    assert principal.username == "alice" and principal.role == "user"

    rotated = auth.refresh_tokens(tokens["refresh_token"])
    assert rotated["access_token"] != tokens["access_token"]
    # old refresh token is revoked after rotation
    with pytest.raises(AuthError):
        auth.refresh_tokens(tokens["refresh_token"])
    auth.close()


def test_lockout_after_failed_attempts(settings):
    auth = AuthService(settings, settings.sqlite_path)
    auth.register("bob", "correct-horse", role="user")
    for _ in range(settings.auth_max_failed_attempts):
        with pytest.raises(AuthError):
            auth.login("bob", "wrong")
    with pytest.raises(AuthLocked):
        auth.login("bob", "correct-horse")  # locked even with the right password
    auth.close()


def test_api_key_mint_and_verify(settings):
    auth = AuthService(settings, settings.sqlite_path)
    minted = auth.create_api_key("ci", role="operator", scopes="tools:execute")
    assert minted["api_key"].startswith("jk_")
    principal = auth.principal_from_api_key(minted["api_key"])
    assert principal.role == "operator" and principal.auth_type == "apikey"
    with pytest.raises(AuthError):
        auth.principal_from_api_key("jk_totally-bogus-key-value")
    auth.close()


def test_oauth_stub_login(settings):
    auth = AuthService(settings, settings.sqlite_path)
    tokens = auth.oauth_login("github", "abc123code")
    assert tokens["user"]["username"].startswith("github-")
    auth.close()
