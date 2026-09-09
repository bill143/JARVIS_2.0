"""Governed long-term memory: confidence, provenance, TTL/decay, pinning,
conflict detection, and user controls (view/edit/delete/export). Tenant-scoped."""

from __future__ import annotations

import json
import sqlite3
import threading
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path

from jarvis_memory.vector_store import embed_text


def _now() -> str:
    return datetime.now(UTC).isoformat()


def _cosine(a: list[float], b: list[float]) -> float:
    return sum(x * y for x, y in zip(a, b, strict=False))


def _parse(ts: str):
    try:
        return datetime.fromisoformat(ts)
    except (ValueError, TypeError):
        return None


class MemoryGovernanceStore:
    def __init__(self, db_path: Path, settings):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.settings = settings
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        self._ensure()

    def _ensure(self) -> None:
        with self._lock:
            self.conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS memory_items (
                    id TEXT PRIMARY KEY, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                    tenant TEXT NOT NULL DEFAULT 'default', user_id TEXT NOT NULL DEFAULT 'default',
                    namespace TEXT NOT NULL DEFAULT '', text TEXT NOT NULL, confidence REAL NOT NULL DEFAULT 0.7,
                    pinned INTEGER NOT NULL DEFAULT 0, ttl_days INTEGER NOT NULL DEFAULT 90,
                    expires_at TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'active',
                    provenance TEXT NOT NULL DEFAULT '{}', last_validated_at TEXT NOT NULL DEFAULT '',
                    embedding TEXT NOT NULL DEFAULT '[]'
                );
                CREATE INDEX IF NOT EXISTS idx_mem_items_ns ON memory_items(namespace);
                CREATE TABLE IF NOT EXISTS memory_conflicts (
                    id TEXT PRIMARY KEY, created_at TEXT NOT NULL, namespace TEXT NOT NULL,
                    item_a TEXT NOT NULL, item_b TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'open',
                    resolution TEXT NOT NULL DEFAULT ''
                );
                """
            )
            self.conn.commit()

    def _namespace(self, tenant: str, user_id: str) -> str:
        return f"{tenant}--{user_id}"

    def add(self, *, tenant: str, user_id: str, text: str, confidence: float | None = None,
            provenance: dict | None = None, ttl_days: int | None = None, pinned: bool = False) -> dict:
        confidence = self.settings.memory_confidence_min if confidence is None else confidence
        ttl_days = ttl_days or self.settings.memory_default_ttl_days
        ns = self._namespace(tenant, user_id)
        item_id = uuid.uuid4().hex[:16]
        now = _now()
        expires = (datetime.now(UTC) + timedelta(days=ttl_days)).isoformat()
        # conflict detection against existing items in the namespace
        conflict = self._detect_conflict(ns, text)
        with self._lock:
            self.conn.execute(
                "INSERT INTO memory_items (id, created_at, updated_at, tenant, user_id, namespace, text, confidence, "
                "pinned, ttl_days, expires_at, status, provenance, last_validated_at, embedding) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, ?, ?)",
                (item_id, now, now, tenant, user_id, ns, text, confidence, 1 if pinned else 0, ttl_days,
                 expires, json.dumps(provenance or {"why": "user_upsert"}), now, json.dumps(embed_text(text))),
            )
            if conflict:
                self.conn.execute(
                    "INSERT INTO memory_conflicts (id, created_at, namespace, item_a, item_b, status) VALUES (?, ?, ?, ?, ?, 'open')",
                    (uuid.uuid4().hex[:12], now, ns, item_id, conflict["id"]))
            self.conn.commit()
        return {"id": item_id, "confidence": confidence, "expires_at": expires,
                "conflict_with": conflict["id"] if conflict else None}

    def _detect_conflict(self, ns: str, text: str) -> dict | None:
        """A near-duplicate embedding with a negation asymmetry signals a conflict."""
        qv = embed_text(text)
        neg = any(w in text.lower() for w in (" not ", " no ", "n't", " never "))
        with self._lock:
            rows = self.conn.execute(
                "SELECT id, text, embedding FROM memory_items WHERE namespace = ? AND status = 'active'", (ns,)).fetchall()
        for rid, rtext, emb in rows:
            try:
                sim = _cosine(qv, json.loads(emb))
            except (json.JSONDecodeError, TypeError):
                continue
            rneg = any(w in rtext.lower() for w in (" not ", " no ", "n't", " never "))
            if sim >= 0.85 and neg != rneg:
                return {"id": rid, "text": rtext, "similarity": round(sim, 3)}
        return None

    def list(self, tenant: str, user_id: str, *, include_expired: bool = False, limit: int = 200) -> list[dict]:
        ns = self._namespace(tenant, user_id)
        with self._lock:
            rows = self.conn.execute(
                "SELECT id, text, confidence, pinned, ttl_days, expires_at, status, provenance, created_at, last_validated_at "
                "FROM memory_items WHERE namespace = ? ORDER BY pinned DESC, created_at DESC LIMIT ?", (ns, limit)).fetchall()
        out = []
        now = datetime.now(UTC)
        for r in rows:
            exp = _parse(r[5])
            expired = bool(r[5]) and exp is not None and exp < now and not r[3]
            if expired and not include_expired:
                continue
            out.append({"id": r[0], "text": r[1], "confidence": r[2], "pinned": bool(r[3]), "ttl_days": r[4],
                        "expires_at": r[5], "status": "expired" if expired else r[6],
                        "provenance": json.loads(r[7]), "created_at": r[8], "last_validated_at": r[9],
                        "decayed_confidence": self._decayed(r[2], r[8], bool(r[3]))})
        return out

    def _decayed(self, confidence: float, created_at: str, pinned: bool) -> float:
        """Time-decay confidence unless pinned (half-life ~ ttl)."""
        if pinned:
            return round(confidence, 4)
        created = _parse(created_at)
        if not created:
            return round(confidence, 4)
        age_days = (datetime.now(UTC) - created).total_seconds() / 86400.0
        factor = 0.5 ** (age_days / max(self.settings.memory_default_ttl_days, 1))
        return round(confidence * factor, 4)

    def get(self, item_id: str) -> dict | None:
        with self._lock:
            r = self.conn.execute(
                "SELECT id, tenant, user_id, text, confidence, pinned, status, provenance FROM memory_items WHERE id = ?",
                (item_id,)).fetchone()
        if not r:
            return None
        return {"id": r[0], "tenant": r[1], "user_id": r[2], "text": r[3], "confidence": r[4],
                "pinned": bool(r[5]), "status": r[6], "provenance": json.loads(r[7])}

    def edit(self, item_id: str, *, text: str | None = None, confidence: float | None = None) -> dict | None:
        fields, params = ["updated_at = ?", "last_validated_at = ?"], [_now(), _now()]
        if text is not None:
            fields += ["text = ?", "embedding = ?"]; params += [text, json.dumps(embed_text(text))]
        if confidence is not None:
            fields.append("confidence = ?"); params.append(confidence)
        params.append(item_id)
        with self._lock:
            self.conn.execute(f"UPDATE memory_items SET {', '.join(fields)} WHERE id = ?", params)
            self.conn.commit()
        return self.get(item_id)

    def pin(self, tenant: str, user_id: str, item_id: str, pinned: bool = True) -> dict | None:
        if pinned and self._pin_count(tenant, user_id) >= self.settings.memory_pin_limit_per_user:
            return {"error": f"pin limit reached ({self.settings.memory_pin_limit_per_user})"}
        with self._lock:
            self.conn.execute("UPDATE memory_items SET pinned = ?, updated_at = ? WHERE id = ?",
                              (1 if pinned else 0, _now(), item_id))
            self.conn.commit()
        return self.get(item_id)

    def _pin_count(self, tenant: str, user_id: str) -> int:
        with self._lock:
            return self.conn.execute(
                "SELECT COUNT(*) FROM memory_items WHERE namespace = ? AND pinned = 1",
                (self._namespace(tenant, user_id),)).fetchone()[0]

    def forget(self, item_id: str) -> bool:
        with self._lock:
            cur = self.conn.execute("UPDATE memory_items SET status = 'forgotten', updated_at = ? WHERE id = ?",
                                    (_now(), item_id))
            self.conn.commit()
        return cur.rowcount > 0

    def export(self, tenant: str, user_id: str) -> dict:
        """Export personal memory data (portability)."""
        items = self.list(tenant, user_id, include_expired=True, limit=10000)
        return {"tenant": tenant, "user_id": user_id, "exported_at": _now(), "count": len(items), "items": items}

    def conflicts(self, tenant: str, user_id: str) -> list[dict]:
        ns = self._namespace(tenant, user_id)
        with self._lock:
            rows = self.conn.execute(
                "SELECT id, item_a, item_b, status, resolution, created_at FROM memory_conflicts WHERE namespace = ? ORDER BY created_at DESC",
                (ns,)).fetchall()
        return [{"id": r[0], "item_a": r[1], "item_b": r[2], "status": r[3], "resolution": r[4], "created_at": r[5]} for r in rows]

    def resolve_conflict(self, conflict_id: str, keep_item: str, resolution: str = "") -> bool:
        with self._lock:
            row = self.conn.execute("SELECT item_a, item_b FROM memory_conflicts WHERE id = ?", (conflict_id,)).fetchone()
            if not row:
                return False
            drop = row[1] if keep_item == row[0] else row[0]
            self.conn.execute("UPDATE memory_items SET status = 'superseded', updated_at = ? WHERE id = ?", (_now(), drop))
            self.conn.execute("UPDATE memory_conflicts SET status = 'resolved', resolution = ? WHERE id = ?",
                              (resolution or f"kept {keep_item}", conflict_id))
            self.conn.commit()
        return True

    def purge_expired(self, tenant: str | None = None) -> int:
        """Decay/TTL enforcement: mark expired non-pinned items."""
        now = _now()
        with self._lock:
            if tenant:
                cur = self.conn.execute(
                    "UPDATE memory_items SET status = 'expired' WHERE pinned = 0 AND status = 'active' "
                    "AND expires_at != '' AND expires_at < ? AND tenant = ?", (now, tenant))
            else:
                cur = self.conn.execute(
                    "UPDATE memory_items SET status = 'expired' WHERE pinned = 0 AND status = 'active' "
                    "AND expires_at != '' AND expires_at < ?", (now,))
            self.conn.commit()
        return cur.rowcount

    def close(self) -> None:
        with self._lock:
            self.conn.close()
