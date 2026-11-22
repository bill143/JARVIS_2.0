"""SQLite metadata persistence: sessions and structured event logs."""

from __future__ import annotations

import json
import sqlite3
import threading
from datetime import UTC, datetime
from pathlib import Path


def _now() -> str:
    return datetime.now(UTC).isoformat()


class MetadataStore:
    def __init__(self, db_path: Path):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        self._ensure_schema()

    def _ensure_schema(self) -> None:
        with self._lock:
            self.conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS event_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    ts TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    payload TEXT NOT NULL DEFAULT '{}'
                );
                CREATE INDEX IF NOT EXISTS idx_event_log_session ON event_log(session_id);
                """
            )
            self.conn.commit()

    def touch_session(self, session_id: str, user_id: str = "default") -> None:
        with self._lock:
            self.conn.execute(
                "INSERT OR IGNORE INTO sessions (id, user_id, created_at) VALUES (?, ?, ?)",
                (session_id, user_id, _now()),
            )
            self.conn.commit()

    def log_session_event(self, session_id: str, kind: str, payload: dict | None = None) -> None:
        with self._lock:
            self.conn.execute(
                "INSERT INTO event_log (session_id, ts, kind, payload) VALUES (?, ?, ?, ?)",
                (session_id, _now(), kind, json.dumps(payload or {}, default=str)[:8000]),
            )
            self.conn.commit()

    def get_session_logs(self, session_id: str, limit: int = 200) -> list[dict]:
        with self._lock:
            rows = self.conn.execute(
                "SELECT ts, kind, payload FROM event_log WHERE session_id = ? ORDER BY id DESC LIMIT ?",
                (session_id, limit),
            ).fetchall()
        out = []
        for ts, kind, payload in rows:
            try:
                parsed = json.loads(payload)
            except json.JSONDecodeError:
                parsed = {"raw": payload}
            out.append({"ts": ts, "kind": kind, "payload": parsed})
        return out

    def close(self) -> None:
        with self._lock:
            self.conn.close()
