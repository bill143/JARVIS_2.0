"""Idempotency keys for mutating endpoints, backed by SQLite (m0006)."""

from __future__ import annotations

import hashlib
import json
import sqlite3
import threading
import time
from pathlib import Path


def request_hash(payload: dict) -> str:
    return hashlib.sha256(json.dumps(payload, sort_keys=True, default=str).encode()).hexdigest()


class IdempotencyStore:
    def __init__(self, db_path: Path, ttl_hours: int = 24):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.ttl_seconds = ttl_hours * 3600
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        self.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS idempotency_keys (
                idem_key TEXT NOT NULL,
                endpoint TEXT NOT NULL,
                request_hash TEXT NOT NULL,
                response TEXT NOT NULL DEFAULT '',
                created_at REAL NOT NULL,
                PRIMARY KEY (idem_key, endpoint)
            )
            """
        )
        self.conn.commit()

    def lookup(self, idem_key: str, endpoint: str, req_hash: str) -> dict | None:
        """Return {'status': 'replay'|'conflict', 'response': ...} or None if new."""
        with self._lock:
            self._purge_expired()
            row = self.conn.execute(
                "SELECT request_hash, response FROM idempotency_keys WHERE idem_key = ? AND endpoint = ?",
                (idem_key, endpoint),
            ).fetchone()
        if row is None:
            return None
        stored_hash, response = row
        if stored_hash != req_hash:
            return {"status": "conflict"}
        return {"status": "replay", "response": json.loads(response) if response else None}

    def store(self, idem_key: str, endpoint: str, req_hash: str, response: dict) -> None:
        with self._lock:
            self.conn.execute(
                "INSERT OR REPLACE INTO idempotency_keys (idem_key, endpoint, request_hash, response, created_at) "
                "VALUES (?, ?, ?, ?, ?)",
                (idem_key, endpoint, req_hash, json.dumps(response, default=str), time.time()),
            )
            self.conn.commit()

    def _purge_expired(self) -> None:
        # Commit immediately: leaving this DELETE in an open transaction would hold
        # a write lock on the whole (WAL) database and deadlock other connections.
        cutoff = time.time() - self.ttl_seconds
        self.conn.execute("DELETE FROM idempotency_keys WHERE created_at < ?", (cutoff,))
        self.conn.commit()

    def close(self) -> None:
        with self._lock:
            self.conn.close()
