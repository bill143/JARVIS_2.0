"""Response cache: exact-match + semantic (embedding) with TTL, tenant-scoped."""

from __future__ import annotations

import hashlib
import json
import sqlite3
import threading
import time
from pathlib import Path

from jarvis_memory.vector_store import embed_text


def _cosine(a: list[float], b: list[float]) -> float:
    return sum(x * y for x, y in zip(a, b, strict=False))


class ResponseCache:
    def __init__(self, db_path: Path, ttl_sec: int = 1800, semantic_threshold: float = 0.98):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.ttl_sec = ttl_sec
        self.semantic_threshold = semantic_threshold
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        self.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS response_cache (
                cache_key TEXT PRIMARY KEY, created_at REAL NOT NULL, tenant TEXT NOT NULL DEFAULT 'default',
                response TEXT NOT NULL DEFAULT '', kind TEXT NOT NULL DEFAULT 'exact', prompt TEXT NOT NULL DEFAULT '',
                embedding TEXT NOT NULL DEFAULT '[]'
            )
            """
        )
        self.conn.commit()

    @staticmethod
    def _key(tenant: str, prompt: str) -> str:
        return hashlib.sha256(f"{tenant}::{prompt.strip().lower()}".encode()).hexdigest()

    def get(self, tenant: str, prompt: str) -> dict | None:
        now = time.time()
        with self._lock:
            self.conn.execute("DELETE FROM response_cache WHERE created_at < ?", (now - self.ttl_sec,))
            self.conn.commit()
            # exact match
            row = self.conn.execute(
                "SELECT response FROM response_cache WHERE cache_key = ?", (self._key(tenant, prompt),)).fetchone()
            if row:
                return {"hit": True, "kind": "exact", "response": json.loads(row[0])}
            # semantic match
            rows = self.conn.execute(
                "SELECT response, embedding FROM response_cache WHERE tenant = ?", (tenant,)).fetchall()
        qv = embed_text(prompt)
        for resp, emb in rows:
            try:
                if _cosine(qv, json.loads(emb)) >= self.semantic_threshold:
                    return {"hit": True, "kind": "semantic", "response": json.loads(resp)}
            except (json.JSONDecodeError, TypeError):
                continue
        return None

    def put(self, tenant: str, prompt: str, response: dict) -> None:
        with self._lock:
            self.conn.execute(
                "INSERT OR REPLACE INTO response_cache (cache_key, created_at, tenant, response, kind, prompt, embedding) "
                "VALUES (?, ?, ?, ?, 'exact', ?, ?)",
                (self._key(tenant, prompt), time.time(), tenant, json.dumps(response, default=str),
                 prompt[:500], json.dumps(embed_text(prompt))),
            )
            self.conn.commit()

    def close(self) -> None:
        with self._lock:
            self.conn.close()
