"""Reranker abstraction: cross-encoder/API reranker interface + deterministic fallback.

The default reranker is a deterministic lexical-overlap scorer (no external
dependency). A real cross-encoder or hosted reranker plugs in behind the same
interface via get_reranker(provider, model).
"""

from __future__ import annotations

import re
from abc import ABC, abstractmethod


def _tokens(text: str) -> set[str]:
    return set(re.findall(r"[a-z0-9]+", text.lower()))


class Reranker(ABC):
    name = "base"

    @abstractmethod
    def score(self, query: str, passage: str) -> float:
        ...

    def rerank(self, query: str, candidates: list[dict], top_n: int) -> list[dict]:
        for c in candidates:
            c["rerank_score"] = round(self.score(query, c.get("text", "")), 4)
        ranked = sorted(candidates, key=lambda c: c["rerank_score"], reverse=True)
        return ranked[:top_n]


class LexicalOverlapReranker(Reranker):
    """Deterministic reranker: Jaccard-like overlap + phrase bonus."""

    name = "lexical-overlap"

    def score(self, query: str, passage: str) -> float:
        q, p = _tokens(query), _tokens(passage)
        if not q or not p:
            return 0.0
        overlap = len(q & p) / len(q | p)
        phrase_bonus = 0.2 if query.lower() in passage.lower() else 0.0
        return min(overlap + phrase_bonus, 1.0)


def get_reranker(provider: str = "", model: str = "") -> Reranker:
    # Seam for a real cross-encoder / hosted reranker; deterministic default.
    return LexicalOverlapReranker()
