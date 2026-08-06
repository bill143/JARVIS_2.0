"""Tests for the BGE cross-encoder reranker."""

from __future__ import annotations

from openjarvis.connectors.bge_reranker import BGEReranker
from openjarvis.tools.storage._stubs import RetrievalResult


def _candidates():
    return [
        RetrievalResult(content="candidate one", score=0.5, source="a"),
        RetrievalResult(content="candidate two", score=0.3, source="b"),
        RetrievalResult(content="candidate three", score=0.1, source="c"),
    ]


def test_rerank_empty_candidates_returns_empty():
    reranker = BGEReranker()
    assert reranker.rerank("query", []) == []


def test_rerank_fallback_preserves_order_and_count():
    reranker = BGEReranker()
    candidates = _candidates()

    result = reranker.rerank("query", candidates, top_k=10)

    assert len(result) == len(candidates)
    assert [r.content for r in result] == [c.content for c in candidates]


def test_rerank_fallback_respects_top_k():
    reranker = BGEReranker()
    candidates = _candidates()

    result = reranker.rerank("query", candidates, top_k=2)

    assert len(result) == 2
    assert [r.content for r in result] == ["candidate one", "candidate two"]


def test_rerank_warns_only_once(caplog):
    reranker = BGEReranker()
    candidates = _candidates()

    with caplog.at_level("WARNING"):
        reranker.rerank("query", candidates)
        reranker.rerank("query", candidates)

    warnings = [r for r in caplog.records if "Falling back" in r.message]
    assert len(warnings) == 1
