"""Users and roles for local auth + RBAC."""

VERSION = 1
NAME = "users_roles"


def UP(conn):
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS users (
            id TEXT PRIMARY KEY,
            username TEXT UNIQUE NOT NULL,
            email TEXT,
            password_hash TEXT NOT NULL DEFAULT '',
            role TEXT NOT NULL DEFAULT 'user',
            tenant TEXT NOT NULL DEFAULT 'default',
            oauth_provider TEXT NOT NULL DEFAULT '',
            oauth_subject TEXT NOT NULL DEFAULT '',
            disabled INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_users_tenant ON users(tenant);
        """
    )


def DOWN(conn):
    conn.executescript("DROP INDEX IF EXISTS idx_users_tenant; DROP TABLE IF EXISTS users;")
