"""Agent-to-agent message bus with persistent, auditable trace (m0009 tables)."""

from __future__ import annotations

import json
import sqlite3
import threading
import uuid
from datetime import UTC, datetime
from pathlib import Path


def _now() -> str:
    return datetime.now(UTC).isoformat()


class MessageBus:
    def __init__(self, db_path: Path):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        self.conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS agent_sessions (
                id TEXT PRIMARY KEY, created_at TEXT NOT NULL, goal TEXT NOT NULL,
                owner TEXT NOT NULL DEFAULT '', tenant TEXT NOT NULL DEFAULT 'default',
                status TEXT NOT NULL DEFAULT 'running', arbitration TEXT NOT NULL DEFAULT '', result TEXT NOT NULL DEFAULT ''
            );
            CREATE TABLE IF NOT EXISTS agent_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, ts TEXT NOT NULL,
                round INTEGER NOT NULL DEFAULT 0, sender TEXT NOT NULL, recipient TEXT NOT NULL DEFAULT 'all',
                role TEXT NOT NULL DEFAULT '', content TEXT NOT NULL DEFAULT '', meta TEXT NOT NULL DEFAULT '{}'
            );
            CREATE INDEX IF NOT EXISTS idx_agent_msgs_session ON agent_messages(session_id);
            """
        )
        self.conn.commit()

    def create_session(self, goal: str, owner: str, tenant: str) -> str:
        sid = uuid.uuid4().hex[:16]
        with self._lock:
            self.conn.execute(
                "INSERT INTO agent_sessions (id, created_at, goal, owner, tenant, status) VALUES (?, ?, ?, ?, ?, 'running')",
                (sid, _now(), goal, owner, tenant))
            self.conn.commit()
        return sid

    def post(self, session_id: str, *, sender: str, content: str, role: str = "", round: int = 0,
             recipient: str = "all", meta: dict | None = None) -> None:
        with self._lock:
            self.conn.execute(
                "INSERT INTO agent_messages (session_id, ts, round, sender, recipient, role, content, meta) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (session_id, _now(), round, sender, recipient, role, content, json.dumps(meta or {}, default=str)))
            self.conn.commit()

    def messages(self, session_id: str) -> list[dict]:
        with self._lock:
            rows = self.conn.execute(
                "SELECT ts, round, sender, recipient, role, content, meta FROM agent_messages WHERE session_id = ? ORDER BY id",
                (session_id,)).fetchall()
        return [{"ts": r[0], "round": r[1], "sender": r[2], "recipient": r[3], "role": r[4],
                 "content": r[5], "meta": json.loads(r[6])} for r in rows]

    def finish(self, session_id: str, arbitration: str, result: dict) -> None:
        with self._lock:
            self.conn.execute(
                "UPDATE agent_sessions SET status = 'completed', arbitration = ?, result = ? WHERE id = ?",
                (arbitration, json.dumps(result, default=str), session_id))
            self.conn.commit()

    def get_session(self, session_id: str) -> dict | None:
        with self._lock:
            r = self.conn.execute(
                "SELECT id, created_at, goal, owner, tenant, status, arbitration, result FROM agent_sessions WHERE id = ?",
                (session_id,)).fetchone()
        if not r:
            return None
        return {"id": r[0], "created_at": r[1], "goal": r[2], "owner": r[3], "tenant": r[4], "status": r[5],
                "arbitration": r[6], "result": json.loads(r[7]) if r[7] else None,
                "messages": self.messages(session_id)}

    def list_sessions(self, tenant: str, limit: int = 50) -> list[dict]:
        with self._lock:
            rows = self.conn.execute(
                "SELECT id FROM agent_sessions WHERE tenant = ? ORDER BY created_at DESC LIMIT ?", (tenant, limit)).fetchall()
        return [self.get_session(r[0]) for r in rows]

    def close(self) -> None:
        with self._lock:
            self.conn.close()
