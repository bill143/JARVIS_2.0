"""Redis-backed queue driver (activated when redis is installed + reachable).

Mirrors the SQLiteQueueDriver interface. Uses a Redis list for the ready queue,
a hash per job for state, and a dead-letter list. Kept intentionally simple —
the SQLite driver is the default fallback for local/offline development.
"""

from __future__ import annotations

import json
import time
import uuid
from datetime import UTC, datetime

READY_KEY = "jarvis:queue:ready"
DEAD_KEY = "jarvis:queue:dead"
JOB_KEY = "jarvis:queue:job:{}"


class RedisQueueDriver:
    backend = "redis"

    def __init__(self, client, max_attempts: int = 3, retry_base: float = 2.0):
        self.r = client
        self.max_attempts = max_attempts
        self.retry_base = retry_base

    def _job_key(self, job_id: str) -> str:
        return JOB_KEY.format(job_id)

    def enqueue(self, kind: str, payload: dict, requester: str = "") -> str:
        job_id = uuid.uuid4().hex[:16]
        job = {
            "id": job_id, "kind": kind, "payload": json.dumps(payload, default=str),
            "status": "queued", "attempts": "0", "max_attempts": str(self.max_attempts),
            "requester": requester, "ts": datetime.now(UTC).isoformat(), "last_error": "", "result": "",
        }
        self.r.hset(self._job_key(job_id), mapping=job)
        self.r.rpush(READY_KEY, job_id)
        return job_id

    def claim(self) -> dict | None:
        job_id = self.r.lpop(READY_KEY)
        if not job_id:
            return None
        job_id = job_id.decode() if isinstance(job_id, bytes) else job_id
        data = self.r.hgetall(self._job_key(job_id))
        if not data:
            return None
        data = {(k.decode() if isinstance(k, bytes) else k): (v.decode() if isinstance(v, bytes) else v) for k, v in data.items()}
        self.r.hset(self._job_key(job_id), "status", "running")
        return {
            "id": job_id, "kind": data["kind"], "payload": json.loads(data["payload"]),
            "attempts": int(data["attempts"]), "max_attempts": int(data["max_attempts"]),
        }

    def complete(self, job_id: str, result: dict) -> None:
        self.r.hset(self._job_key(job_id), mapping={"status": "done", "result": json.dumps(result, default=str)})

    def fail(self, job_id: str, error: str, attempts: int, max_attempts: int) -> str:
        if attempts >= max_attempts:
            self.r.hset(self._job_key(job_id), mapping={"status": "dead", "last_error": error, "attempts": str(attempts)})
            self.r.rpush(DEAD_KEY, job_id)
            return "dead"
        self.r.hset(self._job_key(job_id), mapping={"status": "queued", "last_error": error, "attempts": str(attempts)})
        time.sleep(0)  # backoff handled by the caller loop; keep it simple for Redis
        self.r.rpush(READY_KEY, job_id)
        return "queued"

    def get(self, job_id: str) -> dict | None:
        data = self.r.hgetall(self._job_key(job_id))
        if not data:
            return None
        data = {(k.decode() if isinstance(k, bytes) else k): (v.decode() if isinstance(v, bytes) else v) for k, v in data.items()}
        return {
            "id": job_id, "kind": data.get("kind"), "status": data.get("status"),
            "attempts": int(data.get("attempts", 0)), "max_attempts": int(data.get("max_attempts", 0)),
            "last_error": data.get("last_error", ""), "result": json.loads(data["result"]) if data.get("result") else None,
            "requester": data.get("requester", ""), "ts": data.get("ts"),
        }

    def stats(self) -> dict:
        depth = self.r.llen(READY_KEY)
        dead = self.r.llen(DEAD_KEY)
        return {"backend": self.backend, "queued": depth, "running": 0, "done": 0, "dead": dead, "depth": depth}

    def dead_letters(self, limit: int = 50) -> list[dict]:
        ids = self.r.lrange(DEAD_KEY, 0, limit - 1)
        out = []
        for jid in ids:
            jid = jid.decode() if isinstance(jid, bytes) else jid
            job = self.get(jid)
            if job:
                out.append({"id": job["id"], "kind": job["kind"], "last_error": job["last_error"], "attempts": job["attempts"]})
        return out

    def close(self) -> None:
        try:
            self.r.close()
        except Exception:
            pass
