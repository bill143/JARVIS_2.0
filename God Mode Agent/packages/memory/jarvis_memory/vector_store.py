"""Long-term vector memory. Chroma when installed; deterministic local JSON store otherwise.

Both backends share the same deterministic hashed bag-of-words embedding so
results are reproducible offline and across restarts.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
import threading
from pathlib import Path

EMBED_DIM = 128


def embed_text(text: str) -> list[float]:
    vec = [0.0] * EMBED_DIM
    for token in re.findall(r"[a-z0-9]+", (text or "").lower()):
        idx = int(hashlib.md5(token.encode()).hexdigest(), 16) % EMBED_DIM
        vec[idx] += 1.0
    norm = math.sqrt(sum(v * v for v in vec)) or 1.0
    return [v / norm for v in vec]


def _cosine(a: list[float], b: list[float]) -> float:
    return sum(x * y for x, y in zip(a, b, strict=False))


def make_namespace(user_id: str, session_id: str | None = None) -> str:
    ns = f"{user_id}--{session_id}" if session_id else user_id
    return re.sub(r"[^a-zA-Z0-9_-]", "_", ns)[:60] or "default"


class LocalVectorStore:
    """Flat-file namespaced vector store. Persists to <dir>/local_store.json."""

    backend = "local"

    def __init__(self, persist_dir: Path):
        self.path = Path(persist_dir) / "local_store.json"
        self._lock = threading.Lock()
        self._data: dict[str, list[dict]] = {}
        if self.path.exists():
            try:
                self._data = json.loads(self.path.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, OSError):
                self._data = {}

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(self._data), encoding="utf-8")
        tmp.replace(self.path)

    def upsert(self, namespace: str, record_id: str, text: str, metadata: dict | None = None) -> str:
        with self._lock:
            bucket = self._data.setdefault(namespace, [])
            record = {"id": record_id, "text": text, "metadata": metadata or {}, "embedding": embed_text(text)}
            for i, existing in enumerate(bucket):
                if existing["id"] == record_id:
                    bucket[i] = record
                    break
            else:
                bucket.append(record)
            self._save()
            return record_id

    def search(self, namespace: str, query: str, k: int = 5) -> list[dict]:
        qv = embed_text(query)
        bucket = self._data.get(namespace, [])
        scored = [
            {"id": r["id"], "text": r["text"], "metadata": r["metadata"], "score": round(_cosine(qv, r["embedding"]), 4)}
            for r in bucket
        ]
        scored.sort(key=lambda r: r["score"], reverse=True)
        return scored[:k]


class ChromaVectorStore:
    """Chroma persistent client wrapper using our deterministic embeddings."""

    backend = "chroma"

    def __init__(self, persist_dir: Path):
        import chromadb  # deferred import — optional dependency

        self.client = chromadb.PersistentClient(path=str(persist_dir))

    def _collection(self, namespace: str):
        name = (re.sub(r"[^a-zA-Z0-9_-]", "_", namespace) or "default")[:60].ljust(3, "x")
        return self.client.get_or_create_collection(name=name, metadata={"hnsw:space": "cosine"})

    def upsert(self, namespace: str, record_id: str, text: str, metadata: dict | None = None) -> str:
        col = self._collection(namespace)
        col.upsert(
            ids=[record_id],
            documents=[text],
            embeddings=[embed_text(text)],
            metadatas=[{k: str(v) for k, v in (metadata or {}).items()} or {"_": "1"}],
        )
        return record_id

    def search(self, namespace: str, query: str, k: int = 5) -> list[dict]:
        col = self._collection(namespace)
        if col.count() == 0:
            return []
        res = col.query(query_embeddings=[embed_text(query)], n_results=min(k, col.count()))
        hits: list[dict] = []
        for i, rid in enumerate(res["ids"][0]):
            distance = res["distances"][0][i] if res.get("distances") else 0.0
            hits.append({
                "id": rid,
                "text": res["documents"][0][i] if res.get("documents") else "",
                "metadata": (res["metadatas"][0][i] or {}) if res.get("metadatas") else {},
                "score": round(1.0 - distance, 4),
            })
        return hits


def get_vector_store(persist_dir: Path, prefer: str = "chroma"):
    """Chroma local vector memory by default; flat-file fallback when unavailable."""
    if prefer == "chroma":
        try:
            return ChromaVectorStore(persist_dir)
        except Exception:
            pass
    return LocalVectorStore(persist_dir)
