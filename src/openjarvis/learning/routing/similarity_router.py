"""Similarity-based router — k-nearest-neighbor over labeled example queries.

Third swappable strategy alongside ``"heuristic"`` (rule-based, in
``router.py``) and ``"classifier"`` (trained, in ``classifier.py``). Needs no
training step: seed it with labeled ``(query, model)`` exemplars and it
routes new queries to whichever exemplar's model was used by the most
similar past query, via cosine similarity over the same
:func:`~openjarvis.learning.routing.feature_extraction.featurize` vectors the
classifier uses. This is the model-agnostic analogue of RouteLLM's
``sw_ranking`` (similarity-weighted ranking) router — see ``RESEARCH.md``.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List, Optional, Sequence, Tuple

from openjarvis.core.registry import ModelRegistry, RouterPolicyRegistry
from openjarvis.core.types import RoutingContext
from openjarvis.learning._stubs import RouterPolicy
from openjarvis.learning.routing.feature_extraction import cosine_similarity, featurize


@dataclass(frozen=True)
class Exemplar:
    """A labeled (query, model) pair with its precomputed feature vector."""

    query: str
    model: str
    vector: Tuple[float, ...]


#: Wider than the classifier's default hash dimension. The classifier's
#: weights are *trained*, so gradient descent can learn to ignore noisy
#: hash-collision dimensions; raw cosine similarity has no such
#: correction, so it needs a wider hash space to keep collision noise low.
DEFAULT_SIMILARITY_HASH_DIM = 256

#: Similarity below this is treated as "no real match" and excluded from
#: voting entirely, rather than letting hash-collision noise between an
#: unrelated exemplar and the query outvote a genuinely empty ballot.
DEFAULT_MIN_SIMILARITY = 0.05


class SimilarityRouterPolicy(RouterPolicy):
    """Routes to the model used by the ``k`` most similar labeled exemplars.

    Majority vote among the top-``k`` nearest exemplars above
    *min_similarity*, weighted by similarity (ties broken by summed
    similarity rather than raw count). With no exemplars — or when no
    exemplar clears *min_similarity*, or none of the qualifying models are
    currently available — falls back to default/fallback/first-available,
    same as ``HeuristicRouter`` and ``ClassifierRouterPolicy``.

    Caveat (documented rather than hidden): similarity is cosine distance
    over hashed lexical features (see ``feature_extraction.py``), not a
    real semantic embedding — it catches shared words/n-grams, not
    paraphrases. For production-grade semantic similarity, plug in
    ``sentence-transformers`` (already an optional extra for this
    project's memory backend) as a custom vector source; that integration
    is a documented extension point, not implemented here.
    """

    def __init__(
        self,
        *,
        available_models: Optional[List[str]] = None,
        hash_dim: int = DEFAULT_SIMILARITY_HASH_DIM,
        k: int = 3,
        min_similarity: float = DEFAULT_MIN_SIMILARITY,
        default_model: str = "",
        fallback_model: str = "",
    ) -> None:
        self._available = available_models or []
        self._hash_dim = hash_dim
        self._k = max(1, k)
        self._min_similarity = min_similarity
        self._default = default_model
        self._fallback = fallback_model
        self._exemplars: List[Exemplar] = []

    @property
    def exemplars(self) -> List[Exemplar]:
        return list(self._exemplars)

    def add_exemplar(self, query: str, model: str) -> None:
        """Label a query with the model that should have handled it."""
        vector = tuple(featurize(query, hash_dim=self._hash_dim))
        self._exemplars.append(Exemplar(query=query, model=model, vector=vector))

    def add_exemplars(self, pairs: Sequence[Tuple[str, str]]) -> None:
        for query, model in pairs:
            self.add_exemplar(query, model)

    def clear_exemplars(self) -> None:
        self._exemplars.clear()

    def _fallback_choice(self, available: List[str]) -> str:
        if self._default and (not available or self._default in available):
            return self._default
        if self._fallback and (not available or self._fallback in available):
            return self._fallback
        if available:
            return available[0]
        return self._default or self._fallback or ""

    def select_model(self, context: RoutingContext) -> str:
        available = self._available or list(ModelRegistry.keys())

        if not self._exemplars:
            return self._fallback_choice(available)

        query_vec = featurize(context.query, hash_dim=self._hash_dim)
        scored = sorted(
            (
                (cosine_similarity(query_vec, list(ex.vector)), ex)
                for ex in self._exemplars
            ),
            key=lambda item: item[0],
            reverse=True,
        )
        top_k = scored[: self._k]

        votes: Dict[str, float] = {}
        for similarity, ex in top_k:
            if similarity < self._min_similarity:
                continue
            if available and ex.model not in available:
                continue
            votes[ex.model] = votes.get(ex.model, 0.0) + similarity

        if not votes:
            return self._fallback_choice(available)

        return max(votes.items(), key=lambda item: item[1])[0]


def ensure_registered() -> None:
    """Register SimilarityRouterPolicy as ``"similarity"`` if not already present."""
    if not RouterPolicyRegistry.contains("similarity"):
        RouterPolicyRegistry.register_value("similarity", SimilarityRouterPolicy)


ensure_registered()

__all__ = ["Exemplar", "SimilarityRouterPolicy", "ensure_registered"]
