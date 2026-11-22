"""Durable task queue with Redis driver + SQLite local fallback.

Supports enqueue, worker processing with retry/backoff, dead-letter handling,
and depth/stats for metrics. Long-running jobs: voice processing, vision batch
analysis, long tool tasks. The SQLite driver is the always-available fallback;
the Redis driver activates when redis is installed and reachable.
"""

from __future__ import annotations

import json
import sqlite3
import threading
import time
import uuid
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from pathlib import Path

from jarvis_shared.logging import get_logger, log_event

logger = get_logger("jarvis.queue")

TERMINAL = {"done", "dead"}


class SQLiteQueueDriver:
    backend = "sqlite"

    def __init__(self, db_path: Path, max_attempts: int = 3, retry_base: float = 2.0):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.max_attempts = max_attempts
        self.retry_base = retry_base
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        self.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS queue_jobs (
                id TEXT PRIMARY KEY, ts TEXT NOT NULL, kind TEXT NOT NULL,
                payload TEXT NOT NULL DEFAULT '{}', status TEXT NOT NULL DEFAULT 'queued',
                attempts INTEGER NOT NULL DEFAULT 0, max_attempts INTEGER NOT NULL DEFAULT 3,
                next_attempt_at REAL NOT NULL DEFAULT 0, last_error TEXT NOT NULL DEFAULT '',
                result TEXT NOT NULL DEFAULT '', requester TEXT NOT NULL DEFAULT '',
                updated_at TEXT NOT NULL
            )
            """
        )
        self.conn.commit()

    def enqueue(self, kind: str, payload: dict, requester: str = "") -> str:
        job_id = uuid.uuid4().hex[:16]
        now = datetime.now(UTC).isoformat()
        with self._lock:
            self.conn.execute(
                "INSERT INTO queue_jobs (id, ts, kind, payload, status, max_attempts, next_attempt_at, requester, updated_at) "
                "VALUES (?, ?, ?, ?, 'queued', ?, 0, ?, ?)",
                (job_id, now, kind, json.dumps(payload, default=str), self.max_attempts, requester, now),
            )
            self.conn.commit()
        return job_id

    def claim(self) -> dict | None:
        now = time.time()
        with self._lock:
            row = self.conn.execute(
                "SELECT id, kind, payload, attempts, max_attempts FROM queue_jobs "
                "WHERE status = 'queued' AND next_attempt_at <= ? ORDER BY ts ASC LIMIT 1",
                (now,),
            ).fetchone()
            if not row:
                return None
            self.conn.execute(
                "UPDATE queue_jobs SET status='running', updated_at=? WHERE id=?",
                (datetime.now(UTC).isoformat(), row[0]),
            )
            self.conn.commit()
        return {"id": row[0], "kind": row[1], "payload": json.loads(row[2]), "attempts": row[3], "max_attempts": row[4]}

    def complete(self, job_id: str, result: dict) -> None:
        with self._lock:
            self.conn.execute(
                "UPDATE queue_jobs SET status='done', result=?, updated_at=? WHERE id=?",
                (json.dumps(result, default=str), datetime.now(UTC).isoformat(), job_id),
            )
            self.conn.commit()

    def fail(self, job_id: str, error: str, attempts: int, max_attempts: int) -> str:
        """Reschedule with backoff, or move to dead-letter after max attempts."""
        with self._lock:
            if attempts >= max_attempts:
                self.conn.execute(
                    "UPDATE queue_jobs SET status='dead', last_error=?, attempts=?, updated_at=? WHERE id=?",
                    (error, attempts, datetime.now(UTC).isoformat(), job_id),
                )
                new_status = "dead"
            else:
                delay = self.retry_base ** attempts
                self.conn.execute(
                    "UPDATE queue_jobs SET status='queued', last_error=?, attempts=?, next_attempt_at=?, updated_at=? WHERE id=?",
                    (error, attempts, time.time() + delay, datetime.now(UTC).isoformat(), job_id),
                )
                new_status = "queued"
            self.conn.commit()
        return new_status

    def get(self, job_id: str) -> dict | None:
        with self._lock:
            row = self.conn.execute(
                "SELECT id, kind, status, attempts, max_attempts, last_error, result, requester, ts FROM queue_jobs WHERE id=?",
                (job_id,),
            ).fetchone()
        if not row:
            return None
        return {
            "id": row[0], "kind": row[1], "status": row[2], "attempts": row[3], "max_attempts": row[4],
            "last_error": row[5], "result": json.loads(row[6]) if row[6] else None, "requester": row[7], "ts": row[8],
        }

    def stats(self) -> dict:
        with self._lock:
            rows = self.conn.execute("SELECT status, COUNT(*) FROM queue_jobs GROUP BY status").fetchall()
        counts = {status: count for status, count in rows}
        return {
            "backend": self.backend,
            "queued": counts.get("queued", 0),
            "running": counts.get("running", 0),
            "done": counts.get("done", 0),
            "dead": counts.get("dead", 0),
            "depth": counts.get("queued", 0) + counts.get("running", 0),
        }

    def dead_letters(self, limit: int = 50) -> list[dict]:
        with self._lock:
            rows = self.conn.execute(
                "SELECT id, kind, last_error, attempts FROM queue_jobs WHERE status='dead' ORDER BY updated_at DESC LIMIT ?",
                (limit,),
            ).fetchall()
        return [{"id": r[0], "kind": r[1], "last_error": r[2], "attempts": r[3]} for r in rows]

    def close(self) -> None:
        with self._lock:
            self.conn.close()


def _make_driver(settings, db_path: Path):
    if settings.queue_backend == "redis":
        try:
            import redis  # noqa: F401

            client = redis.Redis.from_url(settings.redis_url, socket_connect_timeout=0.5)
            client.ping()
            from jarvis_reliability.redis_queue import RedisQueueDriver

            log_event(logger, "queue.driver", backend="redis")
            return RedisQueueDriver(client, max_attempts=settings.queue_max_attempts, retry_base=settings.queue_retry_base)
        except Exception as exc:
            log_event(logger, "queue.driver_fallback", reason=str(exc), backend="sqlite")
    return SQLiteQueueDriver(db_path, max_attempts=settings.queue_max_attempts, retry_base=settings.queue_retry_base)


class JobQueue:
    """Queue facade with a pluggable driver and an async worker loop."""

    def __init__(self, settings, db_path: Path, metrics=None):
        self.settings = settings
        self.driver = _make_driver(settings, db_path)
        self.metrics = metrics
        self._handlers: dict[str, Callable[[dict], Awaitable[dict] | dict]] = {}
        self._worker_task = None
        self._stop = False

    @property
    def backend(self) -> str:
        return self.driver.backend

    def register(self, kind: str, handler: Callable[[dict], Awaitable[dict] | dict]) -> None:
        self._handlers[kind] = handler

    def enqueue(self, kind: str, payload: dict, requester: str = "") -> str:
        job_id = self.driver.enqueue(kind, payload, requester=requester)
        if self.metrics:
            self.metrics.counter("jarvis_queue_enqueued_total", labels={"kind": kind}, help="Jobs enqueued")
            self.metrics.gauge("jarvis_queue_depth", self.driver.stats()["depth"], help="Queue depth")
        log_event(logger, "queue.enqueue", job_id=job_id, kind=kind)
        return job_id

    def get(self, job_id: str) -> dict | None:
        return self.driver.get(job_id)

    def stats(self) -> dict:
        return self.driver.stats()

    def dead_letters(self, limit: int = 50) -> list[dict]:
        return self.driver.dead_letters(limit)

    async def process_once(self) -> bool:
        """Claim and run a single job. Returns True if a job was processed."""
        job = self.driver.claim()
        if not job:
            return False
        handler = self._handlers.get(job["kind"])
        attempts = job["attempts"] + 1
        if handler is None:
            self.driver.fail(job["id"], f"no handler for kind '{job['kind']}'", attempts, job["max_attempts"])
            return True
        start = time.perf_counter()
        try:
            result = handler(job["payload"])
            if hasattr(result, "__await__"):
                result = await result
            self.driver.complete(job["id"], result if isinstance(result, dict) else {"result": result})
            if self.metrics:
                self.metrics.counter("jarvis_queue_processed_total", labels={"kind": job["kind"], "status": "done"})
                self.metrics.observe("jarvis_queue_job_seconds", time.perf_counter() - start, labels={"kind": job["kind"]})
        except Exception as exc:
            new_status = self.driver.fail(job["id"], f"{type(exc).__name__}: {exc}", attempts, job["max_attempts"])
            if self.metrics:
                self.metrics.counter("jarvis_queue_processed_total", labels={"kind": job["kind"], "status": new_status})
                if new_status == "dead":
                    self.metrics.counter("jarvis_queue_dead_total", labels={"kind": job["kind"]})
            log_event(logger, "queue.job_failed", job_id=job["id"], status=new_status, error=str(exc))
        return True

    async def run_worker(self, poll_interval: float | None = None) -> None:
        interval = poll_interval if poll_interval is not None else self.settings.queue_poll_interval
        self._stop = False
        log_event(logger, "queue.worker_start", backend=self.backend)
        while not self._stop:
            import asyncio

            processed = await self.process_once()
            if not processed:
                await asyncio.sleep(interval)

    def stop(self) -> None:
        self._stop = True

    def close(self) -> None:
        self.driver.close()


def get_queue(settings, db_path: Path, metrics=None) -> JobQueue:
    return JobQueue(settings, db_path, metrics=metrics)
