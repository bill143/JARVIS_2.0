"""Approval queue for high-risk tool actions."""

VERSION = 5
NAME = "approvals"


def UP(conn):
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS approvals (
            id TEXT PRIMARY KEY,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            requester TEXT NOT NULL,
            tenant TEXT NOT NULL DEFAULT 'default',
            tool TEXT NOT NULL,
            arguments TEXT NOT NULL DEFAULT '{}',
            reason TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT 'pending',
            decided_by TEXT NOT NULL DEFAULT '',
            decision_note TEXT NOT NULL DEFAULT '',
            result TEXT NOT NULL DEFAULT ''
        );
        CREATE INDEX IF NOT EXISTS idx_approvals_status ON approvals(status);
        CREATE INDEX IF NOT EXISTS idx_approvals_tenant ON approvals(tenant);
        """
    )


def DOWN(conn):
    conn.executescript(
        """
        DROP INDEX IF EXISTS idx_approvals_status;
        DROP INDEX IF EXISTS idx_approvals_tenant;
        DROP TABLE IF EXISTS approvals;
        """
    )
