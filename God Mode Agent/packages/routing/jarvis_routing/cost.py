"""Token/cost accounting ledger (per tenant/user/provider/model/day)."""

from __future__ import annotations

import sqlite3
import threading
from datetime import UTC, datetime
from pathlib import Path

# Rough per-1k-token cost (USD).
COST_PER_1K = {"gpt-4o": 0.005, "gpt-4o-mini": 0.0006, "claude-sonnet-5": 0.003,
               "claude-haiku": 0.0008, "mock-1": 0.0}


def estimate_cost(model: str, tokens: int) -> float:
    return round(COST_PER_1K.get(model, 0.001) * tokens / 1000.0, 6)


class CostLedger:
    def __init__(self, db_path: Path):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        self.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS routing_usage (
                id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, day TEXT NOT NULL,
                tenant TEXT NOT NULL DEFAULT 'default', user_id TEXT NOT NULL DEFAULT 'default',
                provider TEXT NOT NULL DEFAULT '', model TEXT NOT NULL DEFAULT '',
                task_type TEXT NOT NULL DEFAULT 'chat', tokens INTEGER NOT NULL DEFAULT 0,
                cost_usd REAL NOT NULL DEFAULT 0, latency_ms REAL NOT NULL DEFAULT 0,
                cache_hit INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        self.conn.execute("CREATE INDEX IF NOT EXISTS idx_routing_day ON routing_usage(day)")
        self.conn.commit()

    def record(self, *, tenant: str, user_id: str, provider: str, model: str, task_type: str,
               tokens: int, latency_ms: float, cache_hit: bool = False) -> float:
        cost = 0.0 if cache_hit else estimate_cost(model, tokens)
        now = datetime.now(UTC)
        with self._lock:
            self.conn.execute(
                "INSERT INTO routing_usage (ts, day, tenant, user_id, provider, model, task_type, tokens, cost_usd, latency_ms, cache_hit) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (now.isoformat(), now.date().isoformat(), tenant, user_id, provider, model, task_type,
                 tokens, cost, latency_ms, 1 if cache_hit else 0),
            )
            self.conn.commit()
        return cost

    def spend_today(self, tenant: str) -> float:
        day = datetime.now(UTC).date().isoformat()
        with self._lock:
            row = self.conn.execute(
                "SELECT COALESCE(SUM(cost_usd), 0) FROM routing_usage WHERE tenant = ? AND day = ?",
                (tenant, day)).fetchone()
        return round(row[0], 6)

    def usage_report(self, tenant: str | None = None) -> dict:
        with self._lock:
            where = "WHERE tenant = ?" if tenant else ""
            params = (tenant,) if tenant else ()
            by_model = self.conn.execute(
                f"SELECT provider, model, COUNT(*), SUM(tokens), ROUND(SUM(cost_usd),6), AVG(latency_ms) "
                f"FROM routing_usage {where} GROUP BY provider, model", params).fetchall()
            by_user = self.conn.execute(
                f"SELECT user_id, ROUND(SUM(cost_usd),6), SUM(tokens) FROM routing_usage {where} GROUP BY user_id", params).fetchall()
            totals = self.conn.execute(
                f"SELECT COUNT(*), SUM(tokens), ROUND(SUM(cost_usd),6), SUM(cache_hit) FROM routing_usage {where}", params).fetchone()
        return {
            "by_model": [{"provider": r[0], "model": r[1], "calls": r[2], "tokens": r[3] or 0,
                          "cost_usd": r[4] or 0.0, "avg_latency_ms": round(r[5] or 0, 2)} for r in by_model],
            "by_user": [{"user_id": r[0], "cost_usd": r[1] or 0.0, "tokens": r[2] or 0} for r in by_user],
            "totals": {"calls": totals[0] or 0, "tokens": totals[1] or 0, "cost_usd": totals[2] or 0.0,
                       "cache_hits": totals[3] or 0},
        }

    def close(self) -> None:
        with self._lock:
            self.conn.close()
