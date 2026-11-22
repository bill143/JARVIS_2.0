"""Compliance: data-class retention policies + evidence export ledger."""

VERSION = 13
NAME = "compliance"


def UP(conn):
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS retention_policies (
            data_class TEXT PRIMARY KEY,
            retention_days INTEGER NOT NULL DEFAULT 180,
            region TEXT NOT NULL DEFAULT 'global',
            deletion_window_days INTEGER NOT NULL DEFAULT 30,
            updated_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS evidence_exports (
            id TEXT PRIMARY KEY,
            created_at TEXT NOT NULL,
            requested_by TEXT NOT NULL,
            tenant TEXT NOT NULL DEFAULT 'default',
            kind TEXT NOT NULL DEFAULT 'full',
            path TEXT NOT NULL DEFAULT '',
            record_count INTEGER NOT NULL DEFAULT 0,
            digest TEXT NOT NULL DEFAULT ''
        );
        """
    )


def DOWN(conn):
    conn.executescript(
        """
        DROP TABLE IF EXISTS evidence_exports;
        DROP TABLE IF EXISTS retention_policies;
        """
    )
