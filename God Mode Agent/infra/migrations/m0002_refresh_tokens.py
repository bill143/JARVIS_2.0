"""Refresh token store for rotation/revocation."""

VERSION = 2
NAME = "refresh_tokens"


def UP(conn):
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS refresh_tokens (
            jti TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            issued_at TEXT NOT NULL,
            expires_at TEXT NOT NULL,
            revoked INTEGER NOT NULL DEFAULT 0,
            rotated_to TEXT NOT NULL DEFAULT ''
        );
        CREATE INDEX IF NOT EXISTS idx_refresh_user ON refresh_tokens(user_id);
        """
    )


def DOWN(conn):
    conn.executescript("DROP INDEX IF EXISTS idx_refresh_user; DROP TABLE IF EXISTS refresh_tokens;")
