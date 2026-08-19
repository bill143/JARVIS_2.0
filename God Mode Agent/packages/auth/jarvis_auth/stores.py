"""SQLite-backed stores for users, refresh tokens, and API keys."""

from __future__ import annotations

import sqlite3
import threading
import uuid
from datetime import UTC, datetime


def _now() -> str:
    return datetime.now(UTC).isoformat()


class UserStore:
    def __init__(self, conn: sqlite3.Connection, lock: threading.Lock):
        self.conn = conn
        self._lock = lock

    def create(self, username: str, password_hash: str, role: str = "user", tenant: str = "default",
               email: str = "", oauth_provider: str = "", oauth_subject: str = "") -> dict:
        user_id = uuid.uuid4().hex[:16]
        with self._lock:
            self.conn.execute(
                "INSERT INTO users (id, username, email, password_hash, role, tenant, oauth_provider, oauth_subject, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (user_id, username, email, password_hash, role, tenant, oauth_provider, oauth_subject, _now()),
            )
            self.conn.commit()
        return {"id": user_id, "username": username, "role": role, "tenant": tenant}

    def get_by_username(self, username: str) -> dict | None:
        with self._lock:
            row = self.conn.execute(
                "SELECT id, username, email, password_hash, role, tenant, disabled FROM users WHERE username = ?",
                (username,),
            ).fetchone()
        if not row:
            return None
        return {"id": row[0], "username": row[1], "email": row[2], "password_hash": row[3],
                "role": row[4], "tenant": row[5], "disabled": bool(row[6])}

    def get_by_id(self, user_id: str) -> dict | None:
        with self._lock:
            row = self.conn.execute(
                "SELECT id, username, email, role, tenant, disabled FROM users WHERE id = ?", (user_id,)
            ).fetchone()
        if not row:
            return None
        return {"id": row[0], "username": row[1], "email": row[2], "role": row[3],
                "tenant": row[4], "disabled": bool(row[5])}

    def count(self) -> int:
        with self._lock:
            return self.conn.execute("SELECT COUNT(*) FROM users").fetchone()[0]

    def list_all(self) -> list[dict]:
        with self._lock:
            rows = self.conn.execute(
                "SELECT id, username, email, role, tenant, disabled, created_at FROM users ORDER BY created_at"
            ).fetchall()
        return [{"id": r[0], "username": r[1], "email": r[2], "role": r[3],
                 "tenant": r[4], "disabled": bool(r[5]), "created_at": r[6]} for r in rows]

    def count_active_admins(self) -> int:
        with self._lock:
            return self.conn.execute(
                "SELECT COUNT(*) FROM users WHERE role = 'admin' AND disabled = 0"
            ).fetchone()[0]

    def set_role(self, user_id: str, role: str) -> None:
        with self._lock:
            self.conn.execute("UPDATE users SET role = ? WHERE id = ?", (role, user_id))
            self.conn.commit()

    def set_disabled(self, user_id: str, disabled: bool) -> None:
        with self._lock:
            self.conn.execute("UPDATE users SET disabled = ? WHERE id = ?", (1 if disabled else 0, user_id))
            self.conn.commit()

    def set_password_hash(self, user_id: str, password_hash: str) -> None:
        with self._lock:
            self.conn.execute("UPDATE users SET password_hash = ? WHERE id = ?", (password_hash, user_id))
            self.conn.commit()

    def get_password_hash(self, user_id: str) -> str | None:
        with self._lock:
            row = self.conn.execute("SELECT password_hash FROM users WHERE id = ?", (user_id,)).fetchone()
        return row[0] if row else None


class RefreshStore:
    def __init__(self, conn: sqlite3.Connection, lock: threading.Lock):
        self.conn = conn
        self._lock = lock

    def add(self, jti: str, user_id: str, expires_at: str) -> None:
        with self._lock:
            self.conn.execute(
                "INSERT OR REPLACE INTO refresh_tokens (jti, user_id, issued_at, expires_at, revoked, rotated_to) "
                "VALUES (?, ?, ?, ?, 0, '')",
                (jti, user_id, _now(), expires_at),
            )
            self.conn.commit()

    def is_active(self, jti: str) -> bool:
        with self._lock:
            row = self.conn.execute("SELECT revoked FROM refresh_tokens WHERE jti = ?", (jti,)).fetchone()
        return bool(row) and row[0] == 0

    def rotate(self, old_jti: str, new_jti: str) -> None:
        with self._lock:
            self.conn.execute(
                "UPDATE refresh_tokens SET revoked = 1, rotated_to = ? WHERE jti = ?", (new_jti, old_jti)
            )
            self.conn.commit()

    def revoke(self, jti: str) -> None:
        with self._lock:
            self.conn.execute("UPDATE refresh_tokens SET revoked = 1 WHERE jti = ?", (jti,))
            self.conn.commit()

    def revoke_all_for_user(self, user_id: str) -> None:
        with self._lock:
            self.conn.execute("UPDATE refresh_tokens SET revoked = 1 WHERE user_id = ?", (user_id,))
            self.conn.commit()


class ApiKeyStore:
    def __init__(self, conn: sqlite3.Connection, lock: threading.Lock):
        self.conn = conn
        self._lock = lock

    def create(self, name: str, key_hash: str, prefix: str, role: str, tenant: str, scopes: str) -> str:
        key_id = uuid.uuid4().hex[:16]
        with self._lock:
            self.conn.execute(
                "INSERT INTO api_keys (id, name, key_hash, prefix, role, tenant, scopes, created_at, revoked) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                (key_id, name, key_hash, prefix, role, tenant, scopes, _now()),
            )
            self.conn.commit()
        return key_id

    def get_by_prefix(self, prefix: str) -> list[dict]:
        with self._lock:
            rows = self.conn.execute(
                "SELECT id, key_hash, role, tenant, scopes, revoked FROM api_keys WHERE prefix = ?", (prefix,)
            ).fetchall()
        return [{"id": r[0], "key_hash": r[1], "role": r[2], "tenant": r[3], "scopes": r[4], "revoked": bool(r[5])}
                for r in rows]

    def revoke(self, key_id: str) -> None:
        with self._lock:
            self.conn.execute("UPDATE api_keys SET revoked = 1 WHERE id = ?", (key_id,))
            self.conn.commit()

    def list_all(self) -> list[dict]:
        """Metadata only — key hashes never leave the store."""
        with self._lock:
            rows = self.conn.execute(
                "SELECT id, name, prefix, role, tenant, scopes, created_at, revoked FROM api_keys ORDER BY created_at"
            ).fetchall()
        return [{"id": r[0], "name": r[1], "prefix": r[2], "role": r[3], "tenant": r[4],
                 "scopes": r[5], "created_at": r[6], "revoked": bool(r[7])} for r in rows]

    def get_meta(self, key_id: str) -> dict | None:
        with self._lock:
            row = self.conn.execute(
                "SELECT id, name, revoked FROM api_keys WHERE id = ?", (key_id,)
            ).fetchone()
        return {"id": row[0], "name": row[1], "revoked": bool(row[2])} if row else None
