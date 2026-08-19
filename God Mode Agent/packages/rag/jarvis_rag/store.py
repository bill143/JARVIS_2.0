"""RAG persistence: documents (versioned, deduped) + chunks with terms/embeddings."""

from __future__ import annotations

import hashlib
import json
import re
import sqlite3
import threading
import uuid
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path

from jarvis_memory.vector_store import embed_text


def _now() -> str:
    return datetime.now(UTC).isoformat()


def term_freqs(text: str) -> dict[str, int]:
    return dict(Counter(re.findall(r"[a-z0-9]+", text.lower())))


class RagStore:
    def __init__(self, db_path: Path):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        self._ensure()

    def _ensure(self) -> None:
        with self._lock:
            self.conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS rag_documents (
                    id TEXT PRIMARY KEY, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                    tenant TEXT NOT NULL DEFAULT 'default', source TEXT NOT NULL DEFAULT '',
                    title TEXT NOT NULL DEFAULT '', owner TEXT NOT NULL DEFAULT '',
                    sensitivity TEXT NOT NULL DEFAULT 'internal', version INTEGER NOT NULL DEFAULT 1,
                    content_hash TEXT NOT NULL DEFAULT '', timestamp TEXT NOT NULL DEFAULT ''
                );
                CREATE INDEX IF NOT EXISTS idx_rag_docs_tenant ON rag_documents(tenant);
                CREATE INDEX IF NOT EXISTS idx_rag_docs_hash ON rag_documents(content_hash);
                CREATE TABLE IF NOT EXISTS rag_chunks (
                    id TEXT PRIMARY KEY, document_id TEXT NOT NULL, tenant TEXT NOT NULL DEFAULT 'default',
                    idx INTEGER NOT NULL DEFAULT 0, text TEXT NOT NULL, strategy TEXT NOT NULL DEFAULT 'fixed',
                    embedding TEXT NOT NULL DEFAULT '[]', terms TEXT NOT NULL DEFAULT '{}',
                    timestamp TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT ''
                );
                CREATE INDEX IF NOT EXISTS idx_rag_chunks_doc ON rag_chunks(document_id);
                CREATE INDEX IF NOT EXISTS idx_rag_chunks_tenant ON rag_chunks(tenant);
                """
            )
            self.conn.commit()

    def find_by_hash(self, tenant: str, content_hash: str) -> dict | None:
        with self._lock:
            row = self.conn.execute(
                "SELECT id, version FROM rag_documents WHERE tenant = ? AND content_hash = ?",
                (tenant, content_hash)).fetchone()
        return {"id": row[0], "version": row[1]} if row else None

    def find_by_source(self, tenant: str, source: str) -> dict | None:
        with self._lock:
            row = self.conn.execute(
                "SELECT id, version FROM rag_documents WHERE tenant = ? AND source = ? ORDER BY version DESC LIMIT 1",
                (tenant, source)).fetchone()
        return {"id": row[0], "version": row[1]} if row else None

    def add_document(self, *, tenant: str, source: str, title: str, content: str, owner: str = "",
                     sensitivity: str = "internal", strategy: str = "semantic", timestamp: str = "",
                     chunker=None) -> dict:
        content_hash = hashlib.sha256(content.encode()).hexdigest()
        # Deduplication: identical content already ingested for this tenant.
        existing = self.find_by_hash(tenant, content_hash)
        if existing:
            return {"id": existing["id"], "version": existing["version"], "deduplicated": True, "chunks": 0}
        # Versioning: bump version if this source was seen before.
        prior = self.find_by_source(tenant, source)
        version = (prior["version"] + 1) if prior else 1

        doc_id = uuid.uuid4().hex[:16]
        ts = timestamp or _now()
        now = _now()
        chunks = chunker(content) if chunker else [content]
        with self._lock:
            self.conn.execute(
                "INSERT INTO rag_documents (id, created_at, updated_at, tenant, source, title, owner, sensitivity, version, content_hash, timestamp) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (doc_id, now, now, tenant, source, title, owner, sensitivity, version, content_hash, ts),
            )
            for i, ch in enumerate(chunks):
                self.conn.execute(
                    "INSERT INTO rag_chunks (id, document_id, tenant, idx, text, strategy, embedding, terms, timestamp, source) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (uuid.uuid4().hex[:16], doc_id, tenant, i, ch, strategy,
                     json.dumps(embed_text(ch)), json.dumps(term_freqs(ch)), ts, source),
                )
            self.conn.commit()
        return {"id": doc_id, "version": version, "deduplicated": False, "chunks": len(chunks)}

    def all_chunks(self, tenant: str) -> list[dict]:
        with self._lock:
            rows = self.conn.execute(
                "SELECT id, document_id, idx, text, embedding, terms, timestamp, source FROM rag_chunks WHERE tenant = ?",
                (tenant,)).fetchall()
        return [
            {"id": r[0], "document_id": r[1], "idx": r[2], "text": r[3],
             "embedding": json.loads(r[4]), "terms": json.loads(r[5]), "timestamp": r[6], "source": r[7]}
            for r in rows
        ]

    def stats(self, tenant: str) -> dict:
        with self._lock:
            docs = self.conn.execute("SELECT COUNT(*) FROM rag_documents WHERE tenant = ?", (tenant,)).fetchone()[0]
            chunks = self.conn.execute("SELECT COUNT(*) FROM rag_chunks WHERE tenant = ?", (tenant,)).fetchone()[0]
        return {"documents": docs, "chunks": chunks}

    def documents(self, tenant: str, limit: int = 100) -> list[dict]:
        with self._lock:
            rows = self.conn.execute(
                "SELECT id, source, title, owner, sensitivity, version, timestamp FROM rag_documents WHERE tenant = ? "
                "ORDER BY updated_at DESC LIMIT ?", (tenant, limit)).fetchall()
        return [{"id": r[0], "source": r[1], "title": r[2], "owner": r[3], "sensitivity": r[4],
                 "version": r[5], "timestamp": r[6]} for r in rows]

    def close(self) -> None:
        with self._lock:
            self.conn.close()
