"""Username/password authentication: bcrypt hashing + SQLite users and sessions.

The store is deliberately small and dependency-light: passwords are hashed with
bcrypt (never stored or logged in plaintext), and sessions are opaque,
server-side tokens with an expiry so they can be revoked. It backs the login
gateway and the session-auth middleware.
"""

from __future__ import annotations

import secrets
import sqlite3
import time
from pathlib import Path
from typing import Optional

import bcrypt

_CREATE_USERS = """\
CREATE TABLE IF NOT EXISTS users (
    username    TEXT PRIMARY KEY,
    pw_hash     TEXT NOT NULL,
    created_at  REAL NOT NULL DEFAULT 0.0
);
"""

_CREATE_SESSIONS = """\
CREATE TABLE IF NOT EXISTS sessions (
    token       TEXT PRIMARY KEY,
    username    TEXT NOT NULL,
    created_at  REAL NOT NULL DEFAULT 0.0,
    expires_at  REAL NOT NULL DEFAULT 0.0
);
"""


def hash_password(password: str) -> str:
    """Hash a password with bcrypt; returns the encoded hash string."""
    return bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")


def verify_password(password: str, pw_hash: str) -> bool:
    """Constant-time verify of ``password`` against a bcrypt hash."""
    try:
        return bcrypt.checkpw(password.encode("utf-8"), pw_hash.encode("utf-8"))
    except (ValueError, TypeError):
        return False


class AuthStore:
    """SQLite-backed users + sessions for the auth gateway."""

    def __init__(self, db_path: str = ":memory:") -> None:
        if db_path != ":memory:":
            Path(db_path).expanduser().parent.mkdir(parents=True, exist_ok=True)
            db_path = str(Path(db_path).expanduser())
        self._conn = sqlite3.connect(db_path, check_same_thread=False)
        self._conn.execute(_CREATE_USERS)
        self._conn.execute(_CREATE_SESSIONS)
        self._conn.commit()

    # -- users -----------------------------------------------------------------

    def create_user(self, username: str, password: str) -> bool:
        """Create a user; returns False if the username already exists."""
        if not username or not password:
            return False
        try:
            self._conn.execute(
                "INSERT INTO users (username, pw_hash, created_at) VALUES (?, ?, ?)",
                (username, hash_password(password), time.time()),
            )
            self._conn.commit()
            return True
        except sqlite3.IntegrityError:
            return False

    def set_password(self, username: str, password: str) -> bool:
        """Reset a user's password. Returns whether the user existed."""
        cur = self._conn.execute(
            "UPDATE users SET pw_hash = ? WHERE username = ?",
            (hash_password(password), username),
        )
        self._conn.commit()
        return cur.rowcount > 0

    def verify_user(self, username: str, password: str) -> bool:
        """True iff the username exists and the password matches."""
        row = self._conn.execute(
            "SELECT pw_hash FROM users WHERE username = ?", (username,)
        ).fetchone()
        if row is None:
            return False
        return verify_password(password, row[0])

    def user_count(self) -> int:
        return self._conn.execute("SELECT COUNT(*) FROM users").fetchone()[0]

    def ensure_admin(self, username: str, password: str) -> bool:
        """Create the admin user on first boot if no users exist yet.

        Returns True if it created the user. Never overwrites an existing user
        (so a rotated password in the DB is not clobbered by a stale env value).
        """
        if self.user_count() == 0 and username and password:
            return self.create_user(username, password)
        return False

    # -- sessions --------------------------------------------------------------

    def create_session(self, username: str, ttl_hours: int = 12) -> str:
        """Issue an opaque session token bound to ``username``."""
        token = secrets.token_urlsafe(32)
        now = time.time()
        self._conn.execute(
            "INSERT INTO sessions (token, username, created_at, expires_at) "
            "VALUES (?, ?, ?, ?)",
            (token, username, now, now + ttl_hours * 3600),
        )
        self._conn.commit()
        return token

    def validate_session(self, token: Optional[str]) -> Optional[str]:
        """Return the username for a valid, unexpired token, else None."""
        if not token:
            return None
        row = self._conn.execute(
            "SELECT username, expires_at FROM sessions WHERE token = ?", (token,)
        ).fetchone()
        if row is None:
            return None
        username, expires_at = row
        if expires_at < time.time():
            self.delete_session(token)
            return None
        return username

    def delete_session(self, token: str) -> None:
        self._conn.execute("DELETE FROM sessions WHERE token = ?", (token,))
        self._conn.commit()

    def purge_expired(self) -> int:
        cur = self._conn.execute(
            "DELETE FROM sessions WHERE expires_at < ?", (time.time(),)
        )
        self._conn.commit()
        return cur.rowcount

    def close(self) -> None:
        self._conn.close()


__all__ = ["AuthStore", "hash_password", "verify_password"]
