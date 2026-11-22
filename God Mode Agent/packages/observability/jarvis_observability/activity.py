"""Activity log — ground-truth SQLite record of agent work (ECHO Command Stage 3).

Every agent task, tool-call batch, TTS synthesis, and (future) deliberation
writes one row on completion. Writers call `get_activity_log().record(...)`,
which never raises: a logging failure must not break the agent path, but it is
always surfaced in the application log.
"""

from __future__ import annotations

import sqlite3
import threading
from datetime import datetime, timedelta, timezone
from pathlib import Path

from jarvis_shared.config import Settings, get_settings
from jarvis_shared.logging import get_logger, log_event

logger = get_logger("jarvis.observability.activity")

ACTIVE_WINDOW_SECONDS = 300  # a row within the last 5 minutes marks an agent active

_SCHEMA = """
CREATE TABLE IF NOT EXISTS activity_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp TEXT NOT NULL,
    agent TEXT NOT NULL,
    task TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('started','completed','failed')),
    model TEXT NOT NULL DEFAULT '',
    detail TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_activity_ts ON activity_log(timestamp);
CREATE INDEX IF NOT EXISTS idx_activity_agent_ts ON activity_log(agent, timestamp);
"""


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _iso(dt: datetime) -> str:
    return dt.isoformat(timespec="milliseconds")


class ActivityLog:
    def __init__(self, db_path: Path):
        self._lock = threading.Lock()
        db_path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(db_path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.executescript(_SCHEMA)
        self._conn.commit()
        self.path = db_path

    def record(self, agent: str, task: str, status: str, model: str = "", detail: str = "") -> None:
        """Insert one row. Never raises — failures are logged, not propagated."""
        try:
            with self._lock:
                self._conn.execute(
                    "INSERT INTO activity_log (timestamp, agent, task, status, model, detail) "
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    (_iso(_utc_now()), str(agent).upper()[:64], str(task)[:500], status,
                     str(model)[:120], str(detail)[:1000]),
                )
                self._conn.commit()
        except Exception as exc:
            log_event(logger, "activity.record_failed", error=str(exc), agent=str(agent), task=str(task)[:80])

    def recent(self, limit: int = 20) -> list[dict]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT id, timestamp, agent, task, status, model, detail "
                "FROM activity_log ORDER BY id DESC LIMIT ?", (limit,),
            ).fetchall()
        return [dict(r) for r in rows]

    def agent_summary(self, configured_agents: list[str]) -> list[dict]:
        """Per-agent truth: last activity, today's counts, active/idle/no_data.

        Covers the union of configured agents (so agents with no rows yet appear
        as no_data — never a fabricated status) and any agent present in the log.
        """
        with self._lock:
            seen = [r["agent"] for r in self._conn.execute("SELECT DISTINCT agent FROM activity_log")]
            agents = sorted({a.upper() for a in configured_agents} | set(seen))
            local_midnight = datetime.now().astimezone().replace(hour=0, minute=0, second=0, microsecond=0)
            today_start = _iso(local_midnight.astimezone(timezone.utc))
            active_cutoff = _iso(_utc_now() - timedelta(seconds=ACTIVE_WINDOW_SECONDS))
            out: list[dict] = []
            for agent in agents:
                last = self._conn.execute(
                    "SELECT timestamp, task, status FROM activity_log WHERE agent = ? "
                    "ORDER BY id DESC LIMIT 1", (agent,),
                ).fetchone()
                counts = {r["status"]: r["n"] for r in self._conn.execute(
                    "SELECT status, COUNT(*) AS n FROM activity_log "
                    "WHERE agent = ? AND timestamp >= ? GROUP BY status", (agent, today_start),
                )}
                if last is None:
                    status = "no_data"
                elif last["timestamp"] >= active_cutoff:
                    status = "active"
                else:
                    status = "idle"
                out.append({
                    "agent": agent,
                    "status": status,
                    "last_timestamp": last["timestamp"] if last else None,
                    "last_task": last["task"] if last else None,
                    "last_status": last["status"] if last else None,
                    "today": {
                        "completed": counts.get("completed", 0),
                        "failed": counts.get("failed", 0),
                        "started": counts.get("started", 0),
                        "total": sum(counts.values()),
                    },
                })
            return out


_instance: ActivityLog | None = None
_instance_lock = threading.Lock()


def get_activity_log(settings: Settings | None = None) -> ActivityLog:
    global _instance
    if _instance is None:
        with _instance_lock:
            if _instance is None:
                s = settings or get_settings()
                _instance = ActivityLog(s.activity_log_path)
                log_event(logger, "activity.opened", path=str(_instance.path))
    return _instance
