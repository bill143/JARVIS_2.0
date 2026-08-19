"""Data-class retention policies + configurable deletion windows + regional flags.

Includes an approval workflow: changes are submitted as change requests and only
applied when an admin approves them (segregation of duties).
"""

from __future__ import annotations

import sqlite3
import threading
import uuid
from datetime import UTC, datetime
from pathlib import Path

# (retention_days, region, deletion_window_days) per data class.
DEFAULT_POLICIES = {
    "audit": (365, "global", 0),          # keep audit 1y, no early deletion window
    "chat": (180, "global", 30),
    "memory": (90, "global", 30),
    "rag": (365, "global", 30),
    "routing_usage": (180, "global", 7),
    "documents": (365, "global", 30),
    "vision": (90, "global", 30),
    "voice": (90, "global", 30),
    "approvals": (365, "global", 0),
    "evals": (365, "global", 30),
    "exports": (365, "global", 0),
}

ALLOWED_REGIONS = ("global", "local", "us", "eu", "uk", "ca", "apac")


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
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS retention_change_requests (id TEXT PRIMARY KEY, created_at TEXT NOT NULL, "
            "requested_by TEXT NOT NULL, op TEXT NOT NULL, data_class TEXT NOT NULL, retention_days INTEGER, "
            "region TEXT, deletion_window_days INTEGER, reason TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'pending', "
            "decided_by TEXT, decided_at TEXT, decision_reason TEXT)"
        )
        self.conn.commit()
        self._seed()

    @staticmethod
    def regions() -> list[str]:
        return list(ALLOWED_REGIONS)

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

    def delete_policy(self, data_class: str) -> bool:
        with self._lock:
            cur = self.conn.execute("DELETE FROM retention_policies WHERE data_class = ?", (data_class,))
            self.conn.commit()
            return cur.rowcount > 0

    def get_policy(self, data_class: str) -> dict:
        with self._lock:
            r = self.conn.execute(
                "SELECT retention_days, region, deletion_window_days FROM retention_policies WHERE data_class = ?",
                (data_class,)).fetchone()
        if r:
            return {"data_class": data_class, "retention_days": r[0], "region": r[1], "deletion_window_days": r[2]}
        return {"data_class": data_class, "retention_days": self.settings.data_retention_days,
                "region": "global", "deletion_window_days": 30}

    # ---------- approval workflow ----------

    def _request_row(self, r) -> dict:
        return {"id": r[0], "created_at": r[1], "requested_by": r[2], "op": r[3], "data_class": r[4],
                "retention_days": r[5], "region": r[6], "deletion_window_days": r[7], "reason": r[8],
                "status": r[9], "decided_by": r[10], "decided_at": r[11], "decision_reason": r[12]}

    def create_request(self, *, requested_by: str, op: str, data_class: str, retention_days: int | None,
                       region: str, deletion_window_days: int | None, reason: str) -> dict:
        req_id = uuid.uuid4().hex[:16]
        now = datetime.now(UTC).isoformat()
        with self._lock:
            self.conn.execute(
                "INSERT INTO retention_change_requests (id, created_at, requested_by, op, data_class, retention_days, "
                "region, deletion_window_days, reason, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending')",
                (req_id, now, requested_by, op, data_class, retention_days, region, deletion_window_days, reason))
            self.conn.commit()
        return self.get_request(req_id)

    def get_request(self, req_id: str) -> dict | None:
        with self._lock:
            r = self.conn.execute(
                "SELECT id, created_at, requested_by, op, data_class, retention_days, region, deletion_window_days, "
                "reason, status, decided_by, decided_at, decision_reason FROM retention_change_requests WHERE id = ?",
                (req_id,)).fetchone()
        return self._request_row(r) if r else None

    def list_requests(self, status: str | None = None, limit: int = 100) -> list[dict]:
        clause = "WHERE status = ?" if status else ""
        params: list = [status] if status else []
        params.append(limit)
        with self._lock:
            rows = self.conn.execute(
                "SELECT id, created_at, requested_by, op, data_class, retention_days, region, deletion_window_days, "
                f"reason, status, decided_by, decided_at, decision_reason FROM retention_change_requests {clause} "
                "ORDER BY created_at DESC LIMIT ?", params).fetchall()
        return [self._request_row(r) for r in rows]

    def decide_request(self, req_id: str, *, decided_by: str, decision: str, decision_reason: str = "") -> dict | None:
        req = self.get_request(req_id)
        if not req:
            return None
        status = "approved" if decision == "approve" else "denied"
        # Apply the change OUTSIDE the request lock (set_policy/delete_policy take the lock themselves).
        if decision == "approve":
            if req["op"] == "upsert" and req["retention_days"] is not None:
                self.set_policy(req["data_class"], int(req["retention_days"]), req["region"] or "global",
                                int(req["deletion_window_days"]) if req["deletion_window_days"] is not None else 30)
            elif req["op"] == "delete":
                self.delete_policy(req["data_class"])
        with self._lock:
            self.conn.execute(
                "UPDATE retention_change_requests SET status = ?, decided_by = ?, decided_at = ?, decision_reason = ? WHERE id = ?",
                (status, decided_by, datetime.now(UTC).isoformat(), decision_reason, req_id))
            self.conn.commit()
        return self.get_request(req_id)

    def close(self) -> None:
        with self._lock:
            self.conn.close()
