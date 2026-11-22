"""Model routing usage accounting + per-tenant budgets + response cache."""

VERSION = 12
NAME = "routing_budgets"


def UP(conn):
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS routing_usage (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts TEXT NOT NULL,
            day TEXT NOT NULL,
            tenant TEXT NOT NULL DEFAULT 'default',
            user_id TEXT NOT NULL DEFAULT 'default',
            provider TEXT NOT NULL DEFAULT '',
            model TEXT NOT NULL DEFAULT '',
            task_type TEXT NOT NULL DEFAULT 'chat',
            tokens INTEGER NOT NULL DEFAULT 0,
            cost_usd REAL NOT NULL DEFAULT 0,
            latency_ms REAL NOT NULL DEFAULT 0,
            cache_hit INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX IF NOT EXISTS idx_routing_day ON routing_usage(day);
        CREATE INDEX IF NOT EXISTS idx_routing_tenant ON routing_usage(tenant);
        CREATE TABLE IF NOT EXISTS tenant_budgets (
            tenant TEXT PRIMARY KEY,
            daily_usd REAL NOT NULL DEFAULT 25.0,
            updated_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS response_cache (
            cache_key TEXT PRIMARY KEY,
            created_at REAL NOT NULL,
            tenant TEXT NOT NULL DEFAULT 'default',
            response TEXT NOT NULL DEFAULT '',
            kind TEXT NOT NULL DEFAULT 'exact'
        );
        """
    )


def DOWN(conn):
    conn.executescript(
        """
        DROP TABLE IF EXISTS response_cache;
        DROP TABLE IF EXISTS tenant_budgets;
        DROP INDEX IF EXISTS idx_routing_tenant;
        DROP INDEX IF EXISTS idx_routing_day;
        DROP TABLE IF EXISTS routing_usage;
        """
    )
