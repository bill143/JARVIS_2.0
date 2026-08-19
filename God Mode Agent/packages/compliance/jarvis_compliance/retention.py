"""Data-class retention policies + configurable deletion windows + regional flags."""

from __future__ import annotations

import sqlite3
import threading
from datetime import UTC, datetime
from pathlib import Path

DEFAULT_POLICIES = {
    "audit": (365, "global", 0),          # keep audit 1y, no early deletion window
    "chat": (180, "global", 30),
    "memory": (90, "global", 30),
    "rag": (365, "global", 30),
    "routing_usage": (180, "global", 7),
}


class RetentionManager:
    def __init__(self, db_path: Path, settings):
        self.db_path = Path(db_path)
        self.settings = settings
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS retention_policies (data_class TEXT PRIMARY KEY, retention_days INTEGER NOT NULL DEFAULT 180, "
            "region TEXT NOT NULL DEFAULT 'global', deletion_window_days INTEGER NOT NULL DEFAULT 30, updated_at TEXT NOT NULL)"
        )
        self.conn.commit()
        self._seed()

    def _seed(self) -> None:
        with self._lock:
            existing = {r[0] for r in self.conn.execute("SELECT data_class FROM retention_policies").fetchall()}
            for cls, (days, region, window) in DEFAULT_POLICIES.items():
                if cls not in existing:
                    self.conn.execute(
                        "INSERT INTO retention_policies (data_class, retention_days, region, deletion_window_days, updated_at) "
                        "VALUES (?, ?, ?, ?, ?)", (cls, days, region, window, datetime.now(UTC).isoformat()))
            self.conn.commit()

    def list_policies(self) -> list[dict]:
        with self._lock:
            rows = self.conn.execute(
                "SELECT data_class, retention_days, region, deletion_window_days, updated_at FROM retention_policies ORDER BY data_class").fetchall()
        return [{"data_class": r[0], "retention_days": r[1], "region": r[2],
                 "deletion_window_days": r[3], "updated_at": r[4]} for r in rows]

    def set_policy(self, data_class: str, retention_days: int, region: str = "global", deletion_window_days: int = 30) -> None:
        with self._lock:
            self.conn.execute(
                "INSERT OR REPLACE INTO retention_policies (data_class, retention_days, region, deletion_window_days, updated_at) "
                "VALUES (?, ?, ?, ?, ?)", (data_class, retention_days, region, deletion_window_days, datetime.now(UTC).isoformat()))
            self.conn.commit()

    def get_policy(self, data_class: str) -> dict:
        with self._lock:
            r = self.conn.execute(
                "SELECT retention_days, region, deletion_window_days FROM retention_policies WHERE data_class = ?",
                (data_class,)).fetchone()
        if r:
            return {"data_class": data_class, "retention_days": r[0], "region": r[1], "deletion_window_days": r[2]}
        return {"data_class": data_class, "retention_days": self.settings.data_retention_days,
                "region": "global", "deletion_window_days": 30}

    def close(self) -> None:
        with self._lock:
            self.conn.close()
