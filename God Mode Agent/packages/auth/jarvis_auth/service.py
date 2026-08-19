"""AuthService: registration, login (with lockout), JWT issue/refresh/rotate,
API-key mint/verify, and principal resolution. SQLite-backed."""

from __future__ import annotations

import hashlib
import secrets
import sqlite3
import threading
import time
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path

from jarvis_auth import jwt_tokens
from jarvis_auth.oauth import get_oauth_provider
from jarvis_auth.passwords import hash_password, verify_password
from jarvis_auth.rbac import Principal
from jarvis_auth.stores import ApiKeyStore, RefreshStore, UserStore
from jarvis_shared.errors import AuthError, AuthLocked, AuthRequired
from jarvis_shared.logging import get_logger, log_event

logger = get_logger("jarvis.auth")


class AuthService:
    def __init__(self, settings, db_path: Path | None = None):
        self.settings = settings
        db_path = db_path or settings.sqlite_path
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(str(db_path), check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        self._ensure_schema()
        self.users = UserStore(self.conn, self._lock)
        self.refresh = RefreshStore(self.conn, self._lock)
        self.api_keys = ApiKeyStore(self.conn, self._lock)
        # in-memory failed-attempt tracker for brute-force lockout
        self._attempts: dict[str, list[float]] = {}

    def _ensure_schema(self) -> None:
        """Create auth tables if migrations haven't run yet (keeps tests self-contained)."""
        with self._lock:
            self.conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS users (
                    id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL, email TEXT,
                    password_hash TEXT NOT NULL DEFAULT '', role TEXT NOT NULL DEFAULT 'user',
                    tenant TEXT NOT NULL DEFAULT 'default', oauth_provider TEXT NOT NULL DEFAULT '',
                    oauth_subject TEXT NOT NULL DEFAULT '', disabled INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS refresh_tokens (
                    jti TEXT PRIMARY KEY, user_id TEXT NOT NULL, issued_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0, rotated_to TEXT NOT NULL DEFAULT ''
                );
                CREATE TABLE IF NOT EXISTS api_keys (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, key_hash TEXT NOT NULL, prefix TEXT NOT NULL,
                    role TEXT NOT NULL DEFAULT 'user', tenant TEXT NOT NULL DEFAULT 'default',
                    scopes TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0
                );
                """
            )
            self.conn.commit()

    # --- registration & login ---

    def register(self, username: str, password: str, role: str = "user", tenant: str = "default", email: str = "") -> dict:
        if self.users.get_by_username(username):
            raise AuthError("username already exists")
        return self.users.create(username, hash_password(password), role=role, tenant=tenant, email=email)

    def ensure_seed_admin(self, username: str, password: str) -> dict | None:
        """Create a default admin on first boot if no users exist."""
        if self.users.count() == 0:
            user = self.users.create(username, hash_password(password), role="admin")
            log_event(logger, "auth.seed_admin", username=username)
            return user
        return None

    def _check_lockout(self, key: str) -> None:
        window = self.settings.auth_lockout_minutes * 60
        now = time.monotonic()
        attempts = [t for t in self._attempts.get(key, []) if now - t < window]
        self._attempts[key] = attempts
        if len(attempts) >= self.settings.auth_max_failed_attempts:
            raise AuthLocked("too many failed login attempts; try again later")

    def _record_failure(self, key: str) -> None:
        self._attempts.setdefault(key, []).append(time.monotonic())

    def login(self, username: str, password: str) -> dict:
        self._check_lockout(username)
        user = self.users.get_by_username(username)
        if not user or user["disabled"] or not verify_password(password, user["password_hash"]):
            self._record_failure(username)
            log_event(logger, "auth.login_failed", username=username)
            raise AuthError("invalid username or password")
        self._attempts.pop(username, None)
        return self.issue_tokens(user)

    def oauth_login(self, provider_name: str, code: str) -> dict:
        provider = get_oauth_provider(provider_name)
        if not provider:
            raise AuthError(f"unknown oauth provider '{provider_name}'")
        profile = provider.exchange_code(code)
        user = self.users.get_by_username(profile.username)
        if not user:
            created = self.users.create(
                profile.username, password_hash="", role="user",
                oauth_provider=profile.provider, oauth_subject=profile.subject, email=profile.email,
            )
            user = self.users.get_by_id(created["id"])
        return self.issue_tokens(user)

    # --- token issuance & refresh ---

    def issue_tokens(self, user: dict) -> dict:
        now = int(time.time())
        access_exp = now + self.settings.jwt_access_ttl_min * 60
        refresh_jti = uuid.uuid4().hex
        refresh_exp_dt = datetime.now(UTC) + timedelta(days=self.settings.jwt_refresh_ttl_days)
        access = jwt_tokens.encode(
            {"sub": user["id"], "username": user["username"], "role": user["role"],
             "tenant": user["tenant"], "type": "access", "iat": now, "exp": access_exp,
             "jti": uuid.uuid4().hex},
            self.settings.effective_jwt_secret,
        )
        refresh = jwt_tokens.encode(
            {"sub": user["id"], "type": "refresh", "jti": refresh_jti, "iat": now,
             "exp": int(refresh_exp_dt.timestamp())},
            self.settings.effective_jwt_refresh_secret,
        )
        self.refresh.add(refresh_jti, user["id"], refresh_exp_dt.isoformat())
        return {
            "access_token": access, "refresh_token": refresh, "token_type": "bearer",
            "expires_in": self.settings.jwt_access_ttl_min * 60,
            "user": {"id": user["id"], "username": user["username"], "role": user["role"], "tenant": user["tenant"]},
        }

    def refresh_tokens(self, refresh_token: str) -> dict:
        try:
            claims = jwt_tokens.decode(refresh_token, self.settings.effective_jwt_refresh_secret)
        except jwt_tokens.TokenError as exc:
            raise AuthError(f"invalid refresh token: {exc}") from None
        if claims.get("type") != "refresh":
            raise AuthError("not a refresh token")
        jti = claims.get("jti", "")
        if not self.refresh.is_active(jti):
            raise AuthError("refresh token revoked or unknown")
        user = self.users.get_by_id(claims["sub"])
        if not user or user["disabled"]:
            raise AuthError("user not found or disabled")
        tokens = self.issue_tokens(user)
        # rotate: revoke the old refresh jti, link to the new one
        new_claims = jwt_tokens.decode(tokens["refresh_token"], self.settings.effective_jwt_refresh_secret)
        self.refresh.rotate(jti, new_claims.get("jti", ""))
        return tokens

    def logout(self, refresh_token: str) -> None:
        try:
            claims = jwt_tokens.decode(refresh_token, self.settings.effective_jwt_refresh_secret, verify_exp=False)
            if claims.get("jti"):
                self.refresh.revoke(claims["jti"])
        except jwt_tokens.TokenError:
            pass

    # --- principal resolution ---

    def principal_from_access(self, token: str) -> Principal:
        try:
            claims = jwt_tokens.decode(token, self.settings.effective_jwt_secret)
        except jwt_tokens.TokenError as exc:
            raise AuthError(f"invalid access token: {exc}") from None
        if claims.get("type") != "access":
            raise AuthError("not an access token")
        return Principal(
            user_id=claims["sub"], username=claims.get("username", ""),
            role=claims.get("role", "user"), tenant=claims.get("tenant", "default"), auth_type="jwt",
        )

    # --- API keys (machine-to-machine) ---

    def create_api_key(self, name: str, role: str = "user", tenant: str = "default", scopes: str = "") -> dict:
        raw = "jk_" + secrets.token_urlsafe(24)
        prefix = raw[:10]
        key_hash = hashlib.sha256(raw.encode()).hexdigest()
        key_id = self.api_keys.create(name, key_hash, prefix, role, tenant, scopes)
        return {"id": key_id, "api_key": raw, "prefix": prefix, "role": role, "tenant": tenant, "scopes": scopes}

    def principal_from_api_key(self, raw_key: str) -> Principal:
        if not raw_key or not raw_key.startswith("jk_"):
            raise AuthError("invalid api key format")
        prefix = raw_key[:10]
        key_hash = hashlib.sha256(raw_key.encode()).hexdigest()
        for candidate in self.api_keys.get_by_prefix(prefix):
            if not candidate["revoked"] and secrets.compare_digest(candidate["key_hash"], key_hash):
                return Principal(
                    user_id=f"apikey:{candidate['id']}", username=f"apikey:{candidate['id']}",
                    role=candidate["role"], tenant=candidate["tenant"], auth_type="apikey",
                    scopes=[s for s in candidate["scopes"].split(",") if s],
                )
        raise AuthError("unknown or revoked api key")

    def dev_bypass_principal(self) -> Principal:
        if not self.settings.allow_dev_auth_bypass:
            raise AuthRequired("authentication required")
        return Principal(user_id="dev", username="dev", role="admin", tenant="default", auth_type="dev-bypass")

    def close(self) -> None:
        with self._lock:
            self.conn.close()
