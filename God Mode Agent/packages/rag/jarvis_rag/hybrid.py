"""Hybrid retrieval: vector similarity + BM25 lexical, fused, reranked, cited.

Pipeline: multi-query expansion -> vector search + BM25 -> reciprocal-rank
fusion -> rerank (top_n) -> freshness scoring -> citations + per-segment
confidence. All deterministic and dependency-free.
"""

from __future__ import annotations

import math
import re
from collections import Counter
from datetime import UTC, datetime

from jarvis_memory.vector_store import embed_text
from jarvis_rag.rerank import get_reranker


def _cosine(a: list[float], b: list[float]) -> float:
    return sum(x * y for x, y in zip(a, b, strict=False))


def _tokenize(text: str) -> list[str]:
    return re.findall(r"[a-z0-9]+", text.lower())


def expand_queries(query: str) -> list[str]:
    """Deterministic multi-query expansion (original + keyword + question forms)."""
    base = query.strip()
    keywords = " ".join(w for w in _tokenize(base) if len(w) > 3)
    variants = [base]
    if keywords and keywords != base.lower():
        variants.append(keywords)
    variants.append(f"what is {base}")
    # de-dup preserving order
    seen, out = set(), []
    for v in variants:
        if v.lower() not in seen:
            seen.add(v.lower())
            out.append(v)
    return out


def _bm25_scores(query_terms: list[str], chunks: list[dict], k1: float = 1.5, b: float = 0.75) -> dict[str, float]:
    n = len(chunks)
    if n == 0:
        return {}
    doc_len = {c["id"]: sum(c["terms"].values()) or 1 for c in chunks}
    avgdl = sum(doc_len.values()) / n
    # document frequency per term
    df: Counter = Counter()
    for c in chunks:
        for term in set(query_terms):
            if term in c["terms"]:
                df[term] += 1
    scores: dict[str, float] = {}
    for c in chunks:
        s = 0.0
        for term in query_terms:
            f = c["terms"].get(term, 0)
            if f == 0:
                continue
            idf = math.log(1 + (n - df[term] + 0.5) / (df[term] + 0.5))
            s += idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * doc_len[c["id"]] / avgdl))
        scores[c["id"]] = s
    return scores


def _freshness(timestamp: str) -> float:
    """Newer timestamps score closer to 1.0; unknown => neutral 0.5."""
    if not timestamp:
        return 0.5
    try:
        ts = datetime.fromisoformat(timestamp)
        age_days = (datetime.now(UTC) - ts).total_seconds() / 86400.0
    except (ValueError, TypeError):
        return 0.5
    return round(1.0 / (1.0 + max(age_days, 0) / 180.0), 4)  # half-life ~180 days


class HybridRetriever:
    def __init__(self, store, settings, metrics=None):
        self.store = store
        self.settings = settings
        self.metrics = metrics
        self.reranker = get_reranker(settings.reranker_provider, settings.reranker_model)

    def retrieve(self, tenant: str, query: str, *, top_k: int | None = None, top_n: int | None = None) -> dict:
        top_k = top_k or self.settings.rag_top_k
        top_n = top_n or self.settings.rag_rerank_top_n
        chunks = self.store.all_chunks(tenant)
        if not chunks:
            return {"query": query, "results": [], "citations": [], "confidence": 0.0,
                    "hybrid": self.settings.rag_hybrid_enabled, "note": "no indexed documents"}

        queries = expand_queries(query) if self.settings.rag_hybrid_enabled else [query]

        # --- vector ranks (best rank across expanded queries) ---
        vec_rank: dict[str, int] = {}
        for q in queries:
            qv = embed_text(q)
            scored = sorted(chunks, key=lambda c: _cosine(qv, c["embedding"]), reverse=True)
            for rank, c in enumerate(scored):
                vec_rank[c["id"]] = min(vec_rank.get(c["id"], 10**9), rank)

        # --- BM25 ranks (lexical) ---
        lex_rank: dict[str, int] = {}
        if self.settings.rag_bm25_enabled:
            qterms = _tokenize(query)
            bm = _bm25_scores(qterms, chunks)
            scored = sorted(chunks, key=lambda c: bm.get(c["id"], 0.0), reverse=True)
            for rank, c in enumerate(scored):
                lex_rank[c["id"]] = rank

        # --- reciprocal rank fusion ---
        rrf_k = 60
        fused: dict[str, float] = {}
        for c in chunks:
            score = 1.0 / (rrf_k + vec_rank.get(c["id"], 10**6))
            if self.settings.rag_bm25_enabled:
                score += 1.0 / (rrf_k + lex_rank.get(c["id"], 10**6))
            fused[c["id"]] = score
        candidates = sorted(chunks, key=lambda c: fused[c["id"]], reverse=True)[:top_k]

        # --- rerank ---
        pre_top = candidates[0]["id"] if candidates else None
        reranked = self.reranker.rerank(query, [dict(c) for c in candidates], top_n)
        post_top = reranked[0]["id"] if reranked else None
        rerank_lift = 1 if (pre_top and post_top and pre_top != post_top) else 0

        # --- freshness + citations + confidence ---
        results = []
        for i, c in enumerate(reranked):
            fresh = _freshness(c.get("timestamp", ""))
            combined = round(0.7 * c.get("rerank_score", 0.0) + 0.3 * fresh, 4)
            results.append({
                "chunk_id": c["id"], "document_id": c["document_id"], "text": c["text"],
                "source": c.get("source", ""), "rerank_score": c.get("rerank_score", 0.0),
                "freshness": fresh, "score": combined, "citation": f"[{i + 1}]",
            })
        citations = [{"marker": r["citation"], "source": r["source"], "document_id": r["document_id"],
                      "snippet": r["text"][:160]} for r in results]
        confidence = round(sum(r["score"] for r in results[:3]) / max(min(3, len(results)), 1), 4) if results else 0.0

        if self.metrics:
            self.metrics.counter("jarvis_rag_queries_total")
            self.metrics.gauge("jarvis_rag_hit_rate", 1.0 if results else 0.0)
            self.metrics.counter("jarvis_rag_rerank_lift_total", value=rerank_lift)
            self.metrics.gauge("jarvis_rag_citation_coverage", 1.0 if citations else 0.0)

        return {"query": query, "expanded_queries": queries, "results": results, "citations": citations,
                "confidence": confidence, "rerank_lift": rerank_lift, "hybrid": self.settings.rag_hybrid_enabled}

    def answer(self, tenant: str, query: str) -> dict:
        """Compose a cited answer with per-segment confidence."""
        retrieval = self.retrieve(tenant, query)
        results = retrieval["results"]
        if not results:
            return {"answer": "I could not find grounded sources for that query.",
                    "citations": [], "confidence": 0.0, "segments": [], "retrieval": retrieval}
        segments = [{"text": r["text"][:200], "citation": r["citation"], "confidence": r["score"]} for r in results[:3]]
        answer = " ".join(f"{s['text']} {s['citation']}" for s in segments)
        return {"answer": answer, "citations": retrieval["citations"], "confidence": retrieval["confidence"],
                "segments": segments, "retrieval": retrieval}
