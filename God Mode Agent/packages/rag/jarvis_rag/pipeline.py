"""Knowledge ingestion pipeline: connectors, chunking, dedup, versioning, metadata."""

from __future__ import annotations

import base64
from datetime import UTC, datetime

from jarvis_rag.chunking import chunk


class IngestionPipeline:
    """Connectors for file/web/doc content -> chunked, deduped, versioned index."""

    def __init__(self, store, settings, audit=None):
        self.store = store
        self.settings = settings
        self.audit = audit

    def ingest_text(self, *, tenant: str, source: str, title: str, content: str, owner: str = "",
                    sensitivity: str = "internal", strategy: str = "semantic", timestamp: str = "") -> dict:
        result = self.store.add_document(
            tenant=tenant, source=source, title=title, content=content, owner=owner,
            sensitivity=sensitivity, strategy=strategy, timestamp=timestamp or datetime.now(UTC).isoformat(),
            chunker=lambda t: chunk(t, strategy=strategy),
        )
        if self.audit:
            self.audit.record("rag", "ingest", actor=owner, tenant=tenant,
                              detail={"source": source, "chunks": result["chunks"], "deduplicated": result["deduplicated"]})
        return result

    def ingest_file_b64(self, *, tenant: str, source: str, title: str, data_b64: str, owner: str = "",
                        sensitivity: str = "internal", strategy: str = "fixed") -> dict:
        try:
            content = base64.b64decode(data_b64).decode("utf-8", errors="replace")
        except Exception as exc:
            return {"error": f"could not decode file: {exc}"}
        return self.ingest_text(tenant=tenant, source=source, title=title, content=content,
                                owner=owner, sensitivity=sensitivity, strategy=strategy)

    def ingest_web(self, *, tenant: str, url: str, content: str, owner: str = "", strategy: str = "semantic") -> dict:
        # Connector abstraction: caller fetches (or mocks) content; we index it.
        return self.ingest_text(tenant=tenant, source=url, title=url, content=content,
                                owner=owner, sensitivity="public", strategy=strategy)

    def reindex(self, tenant: str) -> dict:
        """Scheduled re-indexing hook. Chunks/embeddings are recomputed on ingest,
        so this reports current index stats (idempotent for the deterministic store)."""
        stats = self.store.stats(tenant)
        if self.audit:
            self.audit.record("rag", "reindex", tenant=tenant, detail=stats)
        return {"reindexed": True, **stats}
