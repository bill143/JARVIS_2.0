"""Governed long-term memory: confidence, provenance, TTL/decay, pinning."""

VERSION = 11
NAME = "memory_governance"


def UP(conn):
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS memory_items (
            id TEXT PRIMARY KEY,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            tenant TEXT NOT NULL DEFAULT 'default',
            user_id TEXT NOT NULL DEFAULT 'default',
            namespace TEXT NOT NULL DEFAULT '',
            text TEXT NOT NULL,
            confidence REAL NOT NULL DEFAULT 0.7,
            pinned INTEGER NOT NULL DEFAULT 0,
            ttl_days INTEGER NOT NULL DEFAULT 90,
            expires_at TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT 'active',
            provenance TEXT NOT NULL DEFAULT '{}',
            last_validated_at TEXT NOT NULL DEFAULT '',
            embedding TEXT NOT NULL DEFAULT '[]'
        );
        CREATE INDEX IF NOT EXISTS idx_mem_items_ns ON memory_items(namespace);
        CREATE INDEX IF NOT EXISTS idx_mem_items_tenant ON memory_items(tenant);
        CREATE TABLE IF NOT EXISTS memory_conflicts (
            id TEXT PRIMARY KEY,
            created_at TEXT NOT NULL,
            namespace TEXT NOT NULL,
            item_a TEXT NOT NULL,
            item_b TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'open',
            resolution TEXT NOT NULL DEFAULT ''
        );
        """
    )


def DOWN(conn):
    conn.executescript(
        """
        DROP TABLE IF EXISTS memory_conflicts;
        DROP INDEX IF EXISTS idx_mem_items_tenant;
        DROP INDEX IF EXISTS idx_mem_items_ns;
        DROP TABLE IF EXISTS memory_items;
        """
    )
