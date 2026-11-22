"""Routing policies — model selection based on query characteristics.

Four swappable strategies self-register in ``RouterPolicyRegistry`` at
import time:

- ``"heuristic"`` — rule-based (:class:`~.router.HeuristicRouter`).
- ``"learned"`` — trace-driven online learning
  (:class:`~.learned_router.LearnedRouterPolicy`).
- ``"classifier"`` — trained, RouteLLM-style
  (:class:`~.classifier.ClassifierRouterPolicy`).
- ``"similarity"`` — k-NN over labeled exemplars
  (:class:`~.similarity_router.SimilarityRouterPolicy`).

Use :func:`create_router_policy` to instantiate one by name (e.g. driven
by ``config.learning.routing.policy``), or import the classes directly.
See ``ARCHITECTURE.md`` for how these fit together and ``README.md`` for
a usage walkthrough.
"""

from __future__ import annotations

from typing import Any

from openjarvis.core.registry import RouterPolicyRegistry
from openjarvis.learning.routing import classifier as _classifier
from openjarvis.learning.routing import heuristic_policy as _heuristic_policy
from openjarvis.learning.routing import learned_router as _learned_router
from openjarvis.learning.routing import similarity_router as _similarity_router
from openjarvis.learning.routing.classifier import (
    ClassifierRouterPolicy,
    RouteClassifier,
)
from openjarvis.learning.routing.complexity import (
    ComplexityQueryAnalyzer,
    score_complexity,
)
from openjarvis.learning.routing.learned_router import LearnedRouterPolicy
from openjarvis.learning.routing.model_tiers import (
    ModelTiers,
    capability_score,
    rank_models,
)
from openjarvis.learning.routing.router import (
    DefaultQueryAnalyzer,
    HeuristicRouter,
    build_routing_context,
)
from openjarvis.learning.routing.similarity_router import (
    Exemplar,
    SimilarityRouterPolicy,
)


def ensure_all_registered() -> None:
    """Idempotently register every built-in routing strategy.

    Safe to call repeatedly (each strategy's own ``ensure_registered()``
    is itself idempotent) — this is what lets any caller that only
    imports this package (rather than a specific strategy module) still
    see the full ``RouterPolicyRegistry.keys()`` list.
    """
    _heuristic_policy.ensure_registered()
    _learned_router.ensure_registered()
    _classifier.ensure_registered()
    _similarity_router.ensure_registered()


def create_router_policy(strategy: str, **kwargs: Any) -> Any:
    """Instantiate a registered routing strategy by name.

    The "swappable strategies" factory: swap *strategy* (e.g. sourced
    from ``config.learning.routing.policy``, itself adjustable via
    ``PUT /v1/learning/routing/config`` or
    ``jarvis config set learning.routing.policy <name>``) without
    touching call sites::

        >>> policy = create_router_policy("classifier", cost_threshold=0.6)
        >>> policy = create_router_policy("heuristic", default_model="qwen3:8b")

    Raises ``KeyError`` for an unregistered strategy name.
    """
    ensure_all_registered()
    return RouterPolicyRegistry.create(strategy, **kwargs)


ensure_all_registered()

__all__ = [
    "ClassifierRouterPolicy",
    "ComplexityQueryAnalyzer",
    "DefaultQueryAnalyzer",
    "Exemplar",
    "HeuristicRouter",
    "LearnedRouterPolicy",
    "ModelTiers",
    "RouteClassifier",
    "SimilarityRouterPolicy",
    "build_routing_context",
    "capability_score",
    "create_router_policy",
    "ensure_all_registered",
    "rank_models",
    "score_complexity",
]
