"""Tool audit log: timestamp, args summary, result status, duration."""

from __future__ import annotations

import sqlite3
import threading
from datetime import UTC, datetime
from pathlib import Path


class AuditLog:
    def __init__(self, db_path: Path):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        with self._lock:
            self.conn.execute(
                """
                CREATE TABLE IF NOT EXISTS tool_audit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    tool TEXT NOT NULL,
                    args_summary TEXT NOT NULL,
                    status TEXT NOT NULL,
                    duration_ms REAL NOT NULL
                )
                """
            )
            self.conn.execute("CREATE INDEX IF NOT EXISTS idx_tool_audit_session ON tool_audit(session_id)")
            self.conn.commit()

    def record(self, session_id: str, tool: str, args_summary: str, status: str, duration_ms: float) -> None:
        with self._lock:
            self.conn.execute(
                "INSERT INTO tool_audit (ts, session_id, tool, args_summary, status, duration_ms) VALUES (?, ?, ?, ?, ?, ?)",
                (datetime.now(UTC).isoformat(), session_id, tool, args_summary[:500], status, duration_ms),
            )
            self.conn.commit()

    def for_session(self, session_id: str, limit: int = 100) -> list[dict]:
        with self._lock:
            rows = self.conn.execute(
                "SELECT ts, tool, args_summary, status, duration_ms FROM tool_audit WHERE session_id = ? ORDER BY id DESC LIMIT ?",
                (session_id, limit),
            ).fetchall()
        return [
            {"ts": ts, "tool": tool, "args_summary": args, "status": status, "duration_ms": dur}
            for ts, tool, args, status, dur in rows
        ]

    def close(self) -> None:
        with self._lock:
            self.conn.close()
