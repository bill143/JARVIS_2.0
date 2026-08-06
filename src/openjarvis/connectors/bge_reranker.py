"""BGE cross-encoder reranker — second-stage semantic reranking via FlagEmbedding.

Complements :class:`~openjarvis.connectors.retriever.ColBERTReranker`: where
ColBERT scores via late-interaction MaxSim over token embeddings, BGE runs a
single cross-encoder forward pass per (query, candidate) pair — simpler to
run and no separate embedding cache, at the cost of scoring every candidate
independently rather than reusing cached document embeddings.
"""

from __future__ import annotations

import logging
from typing import List

from openjarvis.connectors.retriever import Reranker
from openjarvis.tools.storage._stubs import RetrievalResult

logger = logging.getLogger(__name__)


class BGEReranker(Reranker):
    """Semantic reranker backed by a BGE cross-encoder (``FlagEmbedding``).

    Lazy-loads the model on first use. If the ``FlagEmbedding`` package is
    not installed, falls back to returning candidates in their incoming
    order (with a warning logged once).

    Parameters
    ----------
    model_name:
        HuggingFace model ID. Defaults to ``"BAAI/bge-reranker-base"``.
    use_fp16:
        Run in half precision when a GPU is available (ignored on CPU).
    """

    def __init__(
        self,
        model_name: str = "BAAI/bge-reranker-base",
        *,
        use_fp16: bool = True,
    ) -> None:
        self._model_name = model_name
        self._use_fp16 = use_fp16
        self._model = None
        self._warned = False

    def _load_model(self) -> bool:
        """Attempt to load the FlagEmbedding reranker. Returns True on success."""
        if self._model is not None:
            return True
        try:
            from FlagEmbedding import FlagReranker

            self._model = FlagReranker(self._model_name, use_fp16=self._use_fp16)
            return True
        except Exception as exc:
            if not self._warned:
                logger.warning(
                    "BGEReranker: failed to load FlagEmbedding (%s)."
                    " Falling back to incoming order.",
                    exc,
                )
                self._warned = True
            return False

    def rerank(
        self,
        query: str,
        candidates: List[RetrievalResult],
        *,
        top_k: int = 10,
    ) -> List[RetrievalResult]:
        """Rerank *candidates* using BGE cross-encoder scores.

        Falls back to incoming order if ``FlagEmbedding`` is unavailable.
        """
        if not candidates:
            return []

        if not self._load_model():
            return candidates[:top_k]

        try:
            pairs = [[query, c.content] for c in candidates]
            scores = self._model.compute_score(pairs, normalize=True)
            if isinstance(scores, float):
                scores = [scores]

            ranked = sorted(zip(scores, candidates), key=lambda x: x[0], reverse=True)
            return [
                RetrievalResult(
                    content=result.content,
                    score=float(score),
                    source=result.source,
                    metadata=result.metadata,
                )
                for score, result in ranked[:top_k]
            ]
        except Exception as exc:
            logger.warning("BGEReranker.rerank failed (%s); using incoming order.", exc)
            return candidates[:top_k]


__all__ = ["BGEReranker"]
