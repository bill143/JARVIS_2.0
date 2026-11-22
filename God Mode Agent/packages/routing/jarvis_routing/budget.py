"""Per-tenant daily budget limits + alerts (backed by the cost ledger)."""

from __future__ import annotations

import sqlite3
import threading
from datetime import UTC, datetime
from pathlib import Path


class BudgetManager:
    def __init__(self, db_path: Path, cost_ledger, default_daily_usd: float = 25.0, alert_ratio: float = 0.8):
        self.db_path = Path(db_path)
        self.cost = cost_ledger
        self.default_daily_usd = default_daily_usd
        self.alert_ratio = alert_ratio
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS tenant_budgets (tenant TEXT PRIMARY KEY, daily_usd REAL NOT NULL DEFAULT 25.0, updated_at TEXT NOT NULL)"
        )
        self.conn.commit()

    def get_limit(self, tenant: str) -> float:
        with self._lock:
            row = self.conn.execute("SELECT daily_usd FROM tenant_budgets WHERE tenant = ?", (tenant,)).fetchone()
        return row[0] if row else self.default_daily_usd

    def set_limit(self, tenant: str, daily_usd: float) -> None:
        with self._lock:
            self.conn.execute(
                "INSERT OR REPLACE INTO tenant_budgets (tenant, daily_usd, updated_at) VALUES (?, ?, ?)",
                (tenant, daily_usd, datetime.now(UTC).isoformat()))
            self.conn.commit()

    def status(self, tenant: str) -> dict:
        limit = self.get_limit(tenant)
        spent = self.cost.spend_today(tenant)
        remaining = round(max(0.0, limit - spent), 6)
        ratio = (spent / limit) if limit > 0 else 0.0
        return {"tenant": tenant, "daily_limit_usd": limit, "spent_today_usd": spent,
                "remaining_usd": remaining, "over_budget": spent >= limit,
                "alert": ratio >= self.alert_ratio, "utilization": round(ratio, 4)}

    def allow_spend(self, tenant: str, estimated_usd: float = 0.0) -> tuple[bool, dict]:
        st = self.status(tenant)
        allowed = (st["spent_today_usd"] + estimated_usd) <= st["daily_limit_usd"]
        return allowed, st

    def close(self) -> None:
        with self._lock:
            self.conn.close()
