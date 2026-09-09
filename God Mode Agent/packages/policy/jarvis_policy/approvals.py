"""Approval workflow store for high-risk tool actions (minimal viable queue)."""

from __future__ import annotations

import json
import sqlite3
import threading
import uuid
from datetime import UTC, datetime
from pathlib import Path


def _now() -> str:
    return datetime.now(UTC).isoformat()


class ApprovalStore:
    def __init__(self, db_path: Path):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        self.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS approvals (
                id TEXT PRIMARY KEY, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                requester TEXT NOT NULL, tenant TEXT NOT NULL DEFAULT 'default', tool TEXT NOT NULL,
                arguments TEXT NOT NULL DEFAULT '{}', reason TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'pending', decided_by TEXT NOT NULL DEFAULT '',
                decision_note TEXT NOT NULL DEFAULT '', result TEXT NOT NULL DEFAULT ''
            )
            """
        )
        self.conn.commit()

    def create(self, requester: str, tenant: str, tool: str, arguments: dict, reason: str) -> dict:
        approval_id = uuid.uuid4().hex[:16]
        now = _now()
        with self._lock:
            self.conn.execute(
                "INSERT INTO approvals (id, created_at, updated_at, requester, tenant, tool, arguments, reason, status) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending')",
                (approval_id, now, now, requester, tenant, tool, json.dumps(arguments, default=str), reason),
            )
            self.conn.commit()
        return self.get(approval_id)

    def get(self, approval_id: str) -> dict | None:
        with self._lock:
            row = self.conn.execute(
                "SELECT id, created_at, updated_at, requester, tenant, tool, arguments, reason, status, decided_by, decision_note, result "
                "FROM approvals WHERE id = ?",
                (approval_id,),
            ).fetchone()
        return self._row(row) if row else None

    def list(self, status: str | None = None, tenant: str | None = None, limit: int = 100) -> list[dict]:
        clauses, params = [], []
        if status:
            clauses.append("status = ?"); params.append(status)
        if tenant:
            clauses.append("tenant = ?"); params.append(tenant)
        where = ("WHERE " + " AND ".join(clauses)) if clauses else ""
        params.append(limit)
        with self._lock:
            rows = self.conn.execute(
                "SELECT id, created_at, updated_at, requester, tenant, tool, arguments, reason, status, decided_by, decision_note, result "
                f"FROM approvals {where} ORDER BY created_at DESC LIMIT ?",
                params,
            ).fetchall()
        return [self._row(r) for r in rows]

    def decide(self, approval_id: str, status: str, decided_by: str, note: str = "") -> dict | None:
        if status not in ("approved", "denied"):
            raise ValueError("status must be 'approved' or 'denied'")
        with self._lock:
            self.conn.execute(
                "UPDATE approvals SET status = ?, decided_by = ?, decision_note = ?, updated_at = ? WHERE id = ? AND status = 'pending'",
                (status, decided_by, note, _now(), approval_id),
            )
            self.conn.commit()
        return self.get(approval_id)

    def set_result(self, approval_id: str, result: dict) -> None:
        with self._lock:
            self.conn.execute(
                "UPDATE approvals SET result = ?, status = 'completed', updated_at = ? WHERE id = ?",
                (json.dumps(result, default=str), _now(), approval_id),
            )
            self.conn.commit()

    @staticmethod
    def _row(row) -> dict:
        try:
            arguments = json.loads(row[6])
        except json.JSONDecodeError:
            arguments = {}
        try:
            result = json.loads(row[11]) if row[11] else None
        except json.JSONDecodeError:
            result = None
        return {
            "id": row[0], "created_at": row[1], "updated_at": row[2], "requester": row[3], "tenant": row[4],
            "tool": row[5], "arguments": arguments, "reason": row[7], "status": row[8],
            "decided_by": row[9], "decision_note": row[10], "result": result,
        }

    def close(self) -> None:
        with self._lock:
            self.conn.close()
