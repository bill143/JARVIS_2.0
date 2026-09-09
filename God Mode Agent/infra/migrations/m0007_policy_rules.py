"""Org/user-level policy rules (allowlist/denylist/require_approval)."""

VERSION = 7
NAME = "policy_rules"


def UP(conn):
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS policy_rules (
            id TEXT PRIMARY KEY,
            scope TEXT NOT NULL DEFAULT 'org',
            subject TEXT NOT NULL DEFAULT '*',
            tool TEXT NOT NULL DEFAULT '*',
            action TEXT NOT NULL DEFAULT 'deny',
            priority INTEGER NOT NULL DEFAULT 100,
            note TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_policy_scope ON policy_rules(scope);
        """
    )


def DOWN(conn):
    conn.executescript("DROP INDEX IF EXISTS idx_policy_scope; DROP TABLE IF EXISTS policy_rules;")
