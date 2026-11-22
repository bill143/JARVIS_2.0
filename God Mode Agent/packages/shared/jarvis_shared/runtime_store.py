"""Persisted runtime settings (Run 2): small key/value store in jarvis.db.

Holds operator-editable state that must hot-swap without a restart and
survive one: model tier overrides (`model.voice`, `model.console`) and
per-agent voice overrides (`agent_voice.<AGENT>`). Reads are cheap enough
to do per request — that is what makes the hot-swap work.
"""

from __future__ import annotations

import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path

from jarvis_shared.config import Settings, get_settings

_SCHEMA = """
CREATE TABLE IF NOT EXISTS runtime_settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
"""


class RuntimeStore:
    def __init__(self, db_path: Path):
        self._lock = threading.Lock()
        self._conn = sqlite3.connect(str(db_path), check_same_thread=False)
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.executescript(_SCHEMA)
        self._conn.commit()

    def get(self, key: str, default: str = "") -> str:
        with self._lock:
            row = self._conn.execute(
                "SELECT value FROM runtime_settings WHERE key = ?", (key,)
            ).fetchone()
        return row[0] if row else default

    def set(self, key: str, value: str) -> None:
        now = datetime.now(timezone.utc).isoformat(timespec="seconds")
        with self._lock:
            self._conn.execute(
                "INSERT INTO runtime_settings (key, value, updated_at) VALUES (?, ?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
                (key, value, now),
            )
            self._conn.commit()

    def delete(self, key: str) -> None:
        with self._lock:
            self._conn.execute("DELETE FROM runtime_settings WHERE key = ?", (key,))
            self._conn.commit()

    def all(self, prefix: str = "") -> dict[str, str]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT key, value FROM runtime_settings WHERE key LIKE ?", (f"{prefix}%",)
            ).fetchall()
        return dict(rows)


_instance: RuntimeStore | None = None
_instance_lock = threading.Lock()


def get_runtime_store(settings: Settings | None = None) -> RuntimeStore:
    global _instance
    if _instance is None:
        with _instance_lock:
            if _instance is None:
                s = settings or get_settings()
                _instance = RuntimeStore(s.sqlite_path)
    return _instance
