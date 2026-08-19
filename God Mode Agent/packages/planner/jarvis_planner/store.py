"""Workflow persistence: workflows, steps, checkpoints (replay/resume)."""

from __future__ import annotations

import json
import sqlite3
import threading
import uuid
from datetime import UTC, datetime
from pathlib import Path


def _now() -> str:
    return datetime.now(UTC).isoformat()


class WorkflowStore:
    def __init__(self, db_path: Path):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        self._ensure()

    def _ensure(self) -> None:
        with self._lock:
            self.conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS workflows (
                    id TEXT PRIMARY KEY, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                    goal TEXT NOT NULL, mode TEXT NOT NULL DEFAULT 'plan-and-execute',
                    status TEXT NOT NULL DEFAULT 'pending', owner TEXT NOT NULL DEFAULT '',
                    tenant TEXT NOT NULL DEFAULT 'default', cursor INTEGER NOT NULL DEFAULT 0,
                    result TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT ''
                );
                CREATE TABLE IF NOT EXISTS workflow_steps (
                    id TEXT PRIMARY KEY, workflow_id TEXT NOT NULL, idx INTEGER NOT NULL,
                    name TEXT NOT NULL, action TEXT NOT NULL DEFAULT 'noop',
                    arguments TEXT NOT NULL DEFAULT '{}', depends_on TEXT NOT NULL DEFAULT '[]',
                    status TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0,
                    max_attempts INTEGER NOT NULL DEFAULT 2, result TEXT NOT NULL DEFAULT '',
                    error TEXT NOT NULL DEFAULT '', updated_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_wf_steps_wf ON workflow_steps(workflow_id);
                CREATE TABLE IF NOT EXISTS workflow_checkpoints (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, workflow_id TEXT NOT NULL,
                    ts TEXT NOT NULL, cursor INTEGER NOT NULL, snapshot TEXT NOT NULL DEFAULT '{}'
                );
                """
            )
            self.conn.commit()

    def create(self, goal: str, mode: str, graph, owner: str, tenant: str) -> dict:
        wf_id = uuid.uuid4().hex[:16]
        now = _now()
        with self._lock:
            self.conn.execute(
                "INSERT INTO workflows (id, created_at, updated_at, goal, mode, status, owner, tenant, cursor) "
                "VALUES (?, ?, ?, ?, ?, 'pending', ?, ?, 0)",
                (wf_id, now, now, goal, mode, owner, tenant),
            )
            for i, node in enumerate(graph.topological_order()):
                self.conn.execute(
                    "INSERT INTO workflow_steps (id, workflow_id, idx, name, action, arguments, depends_on, status, updated_at) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', ?)",
                    (node.id, wf_id, i, node.name, node.action,
                     json.dumps(node.arguments, default=str), json.dumps(node.depends_on), now),
                )
            self.conn.commit()
        return self.get(wf_id)

    def get(self, wf_id: str) -> dict | None:
        with self._lock:
            row = self.conn.execute(
                "SELECT id, created_at, updated_at, goal, mode, status, owner, tenant, cursor, result, error "
                "FROM workflows WHERE id = ?", (wf_id,)).fetchone()
            if not row:
                return None
            steps = self.conn.execute(
                "SELECT id, idx, name, action, arguments, depends_on, status, attempts, max_attempts, result, error "
                "FROM workflow_steps WHERE workflow_id = ? ORDER BY idx", (wf_id,)).fetchall()
        return {
            "id": row[0], "created_at": row[1], "updated_at": row[2], "goal": row[3], "mode": row[4],
            "status": row[5], "owner": row[6], "tenant": row[7], "cursor": row[8],
            "result": json.loads(row[9]) if row[9] else None, "error": row[10],
            "steps": [
                {"id": s[0], "idx": s[1], "name": s[2], "action": s[3], "arguments": json.loads(s[4]),
                 "depends_on": json.loads(s[5]), "status": s[6], "attempts": s[7], "max_attempts": s[8],
                 "result": json.loads(s[9]) if s[9] else None, "error": s[10]}
                for s in steps
            ],
        }

    def list(self, tenant: str | None = None, limit: int = 100) -> list[dict]:
        with self._lock:
            if tenant:
                rows = self.conn.execute(
                    "SELECT id FROM workflows WHERE tenant = ? ORDER BY created_at DESC LIMIT ?", (tenant, limit)).fetchall()
            else:
                rows = self.conn.execute("SELECT id FROM workflows ORDER BY created_at DESC LIMIT ?", (limit,)).fetchall()
        return [self.get(r[0]) for r in rows]

    def set_status(self, wf_id: str, status: str) -> None:
        with self._lock:
            self.conn.execute("UPDATE workflows SET status = ?, updated_at = ? WHERE id = ?", (status, _now(), wf_id))
            self.conn.commit()

    def set_result(self, wf_id: str, result: dict) -> None:
        with self._lock:
            self.conn.execute("UPDATE workflows SET result = ?, status = 'completed', updated_at = ? WHERE id = ?",
                              (json.dumps(result, default=str), _now(), wf_id))
            self.conn.commit()

    def set_error(self, wf_id: str, error: str) -> None:
        with self._lock:
            self.conn.execute("UPDATE workflows SET error = ?, status = 'failed', updated_at = ? WHERE id = ?",
                              (error, _now(), wf_id))
            self.conn.commit()

    def update_step(self, step_id: str, *, status: str, result=None, error: str = "", attempts: int | None = None) -> None:
        with self._lock:
            fields = ["status = ?", "updated_at = ?"]
            params: list = [status, _now()]
            if result is not None:
                fields.append("result = ?"); params.append(json.dumps(result, default=str))
            if error:
                fields.append("error = ?"); params.append(error)
            if attempts is not None:
                fields.append("attempts = ?"); params.append(attempts)
            params.append(step_id)
            self.conn.execute(f"UPDATE workflow_steps SET {', '.join(fields)} WHERE id = ?", params)
            self.conn.commit()

    def checkpoint(self, wf_id: str, cursor: int, snapshot: dict) -> None:
        with self._lock:
            self.conn.execute("UPDATE workflows SET cursor = ?, updated_at = ? WHERE id = ?", (cursor, _now(), wf_id))
            self.conn.execute(
                "INSERT INTO workflow_checkpoints (workflow_id, ts, cursor, snapshot) VALUES (?, ?, ?, ?)",
                (wf_id, _now(), cursor, json.dumps(snapshot, default=str)))
            self.conn.commit()

    def checkpoints(self, wf_id: str) -> list[dict]:
        with self._lock:
            rows = self.conn.execute(
                "SELECT ts, cursor, snapshot FROM workflow_checkpoints WHERE workflow_id = ? ORDER BY id", (wf_id,)).fetchall()
        return [{"ts": r[0], "cursor": r[1], "snapshot": json.loads(r[2])} for r in rows]

    def close(self) -> None:
        with self._lock:
            self.conn.close()
