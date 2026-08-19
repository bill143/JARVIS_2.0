"""Tamper-evident, hash-chained governance audit log.

Each entry's hash = sha256(prev_hash + canonical_json(entry_without_hash)).
verify() walks the chain and reports the first broken link, giving basic
tamper-evidence. Records auth events, policy decisions, approvals, tool
executions, and memory mutations.
"""

from __future__ import annotations

import hashlib
import json
import sqlite3
import threading
from datetime import UTC, datetime
from pathlib import Path

from jarvis_shared.redaction import mask_secrets

GENESIS_HASH = "0" * 64


def _canonical(entry: dict) -> str:
    return json.dumps(entry, sort_keys=True, ensure_ascii=False, separators=(",", ":"), default=str)


class AuditChain:
    def __init__(self, db_path: Path, enable_hash_chain: bool = True):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.enable_hash_chain = enable_hash_chain
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        self.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS audit_chain (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts TEXT NOT NULL,
                category TEXT NOT NULL,
                actor TEXT NOT NULL DEFAULT '',
                tenant TEXT NOT NULL DEFAULT 'default',
                action TEXT NOT NULL,
                detail TEXT NOT NULL DEFAULT '{}',
                prev_hash TEXT NOT NULL DEFAULT '',
                hash TEXT NOT NULL
            )
            """
        )
        self.conn.commit()

    def _last_hash(self) -> str:
        row = self.conn.execute("SELECT hash FROM audit_chain ORDER BY id DESC LIMIT 1").fetchone()
        return row[0] if row else GENESIS_HASH

    def record(self, category: str, action: str, *, actor: str = "", tenant: str = "default", detail: dict | None = None) -> dict:
        ts = datetime.now(UTC).isoformat()
        safe_detail = json.loads(mask_secrets(json.dumps(detail or {}, default=str)))
        with self._lock:
            prev_hash = self._last_hash()
            entry = {
                "ts": ts, "category": category, "actor": actor, "tenant": tenant,
                "action": action, "detail": safe_detail, "prev_hash": prev_hash,
            }
            entry_hash = hashlib.sha256((prev_hash + _canonical(entry)).encode()).hexdigest() if self.enable_hash_chain else ""
            self.conn.execute(
                "INSERT INTO audit_chain (ts, category, actor, tenant, action, detail, prev_hash, hash) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (ts, category, actor, tenant, action, json.dumps(safe_detail, default=str), prev_hash, entry_hash),
            )
            self.conn.commit()
            return {**entry, "hash": entry_hash}

    def query(self, *, category: str | None = None, actor: str | None = None, tenant: str | None = None, limit: int = 100) -> list[dict]:
        clauses, params = [], []
        if category:
            clauses.append("category = ?"); params.append(category)
        if actor:
            clauses.append("actor = ?"); params.append(actor)
        if tenant:
            clauses.append("tenant = ?"); params.append(tenant)
        where = ("WHERE " + " AND ".join(clauses)) if clauses else ""
        params.append(limit)
        with self._lock:
            rows = self.conn.execute(
                f"SELECT id, ts, category, actor, tenant, action, detail, prev_hash, hash "
                f"FROM audit_chain {where} ORDER BY id DESC LIMIT ?",
                params,
            ).fetchall()
        out = []
        for r in rows:
            try:
                detail = json.loads(r[6])
            except json.JSONDecodeError:
                detail = {"raw": r[6]}
            out.append({
                "id": r[0], "ts": r[1], "category": r[2], "actor": r[3], "tenant": r[4],
                "action": r[5], "detail": detail, "prev_hash": r[7], "hash": r[8],
            })
        return out

    def verify(self) -> dict:
        """Walk the chain; report tamper status and the first broken id if any."""
        if not self.enable_hash_chain:
            return {"ok": True, "hash_chain": "disabled", "entries": 0}
        with self._lock:
            rows = self.conn.execute(
                "SELECT ts, category, actor, tenant, action, detail, prev_hash, hash FROM audit_chain ORDER BY id ASC"
            ).fetchall()
        prev = GENESIS_HASH
        for i, r in enumerate(rows, start=1):
            entry = {
                "ts": r[0], "category": r[1], "actor": r[2], "tenant": r[3],
                "action": r[4], "detail": json.loads(r[5]), "prev_hash": r[6],
            }
            expected = hashlib.sha256((prev + _canonical(entry)).encode()).hexdigest()
            if r[6] != prev or r[7] != expected:
                return {"ok": False, "entries": len(rows), "broken_at": i}
            prev = r[7]
        return {"ok": True, "entries": len(rows), "head": prev}

    def close(self) -> None:
        with self._lock:
            self.conn.close()
