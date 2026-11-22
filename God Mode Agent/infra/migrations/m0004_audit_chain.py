"""Hash-chained governance audit log (tamper-evident)."""

VERSION = 4
NAME = "audit_chain"


def UP(conn):
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS audit_chain (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts TEXT NOT NULL,
            category TEXT NOT NULL,
            actor TEXT NOT NULL DEFAULT '',
            tenant TEXT NOT NULL DEFAULT 'default',
            action TEXT NOT NULL,
            detail TEXT NOT NULL DEFAULT '{}',
            prev_hash TEXT NOT NULL DEFAULT '',
            hash TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_audit_category ON audit_chain(category);
        CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_chain(actor);
        CREATE INDEX IF NOT EXISTS idx_audit_tenant ON audit_chain(tenant);
        """
    )


def DOWN(conn):
    conn.executescript(
        """
        DROP INDEX IF EXISTS idx_audit_category;
        DROP INDEX IF EXISTS idx_audit_actor;
        DROP INDEX IF EXISTS idx_audit_tenant;
        DROP TABLE IF EXISTS audit_chain;
        """
    )
