"""Idempotency keys and durable queue jobs (fallback local driver storage)."""

VERSION = 6
NAME = "idempotency_queue"


def UP(conn):
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS idempotency_keys (
            idem_key TEXT NOT NULL,
            endpoint TEXT NOT NULL,
            request_hash TEXT NOT NULL,
            response TEXT NOT NULL DEFAULT '',
            created_at REAL NOT NULL,
            PRIMARY KEY (idem_key, endpoint)
        );
        CREATE TABLE IF NOT EXISTS queue_jobs (
            id TEXT PRIMARY KEY,
            ts TEXT NOT NULL,
            kind TEXT NOT NULL,
            payload TEXT NOT NULL DEFAULT '{}',
            status TEXT NOT NULL DEFAULT 'queued',
            attempts INTEGER NOT NULL DEFAULT 0,
            max_attempts INTEGER NOT NULL DEFAULT 3,
            next_attempt_at REAL NOT NULL DEFAULT 0,
            last_error TEXT NOT NULL DEFAULT '',
            result TEXT NOT NULL DEFAULT '',
            requester TEXT NOT NULL DEFAULT '',
            updated_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_queue_status ON queue_jobs(status);
        """
    )


def DOWN(conn):
    conn.executescript(
        """
        DROP INDEX IF EXISTS idx_queue_status;
        DROP TABLE IF EXISTS queue_jobs;
        DROP TABLE IF EXISTS idempotency_keys;
        """
    )
