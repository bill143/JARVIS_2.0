"""Scoped machine-to-machine API keys."""

VERSION = 3
NAME = "api_keys"


def UP(conn):
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS api_keys (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            key_hash TEXT NOT NULL,
            prefix TEXT NOT NULL,
            role TEXT NOT NULL DEFAULT 'user',
            tenant TEXT NOT NULL DEFAULT 'default',
            scopes TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL,
            revoked INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX IF NOT EXISTS idx_apikeys_prefix ON api_keys(prefix);
        """
    )


def DOWN(conn):
    conn.executescript("DROP INDEX IF EXISTS idx_apikeys_prefix; DROP TABLE IF EXISTS api_keys;")
