"""Trained classifier router — RouteLLM-style strong/weak model routing.

``RouteClassifier`` learns to predict ``P(strong model wins)`` for a query
from labeled (query, label) pairs — a *trained* model, not keyword rules
(``HeuristicRouter`` already covers the rule-based strategy). Two backends
share one linear (weights, bias) representation, so a model trained with
either backend loads and runs identically without needing that backend
installed at inference time:

- ``"simple"`` (default): hashed-feature logistic regression trained with
  plain-Python gradient descent. Zero extra dependencies.
- ``"sklearn"``: ``sklearn.linear_model.LogisticRegression`` for better
  accuracy on larger datasets. Requires the ``learning-router-classifier``
  extra only at *training* time — the resulting coefficients are exported
  to the same plain-float format ``"simple"`` uses, so loading a
  sklearn-trained model never requires sklearn.

See ``RESEARCH.md`` for why a pretrained transformer checkpoint (RouteLLM's
``bert``/``causal_llm`` routers) isn't the shipped default: it would pull
``torch``/``transformers`` into the base install for a routing feature that
otherwise has none, and this task can't verify GPU/network access to
actually download and run one.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

from openjarvis.core.registry import ModelRegistry, RouterPolicyRegistry
from openjarvis.core.types import RoutingContext
from openjarvis.learning._stubs import RouterPolicy
from openjarvis.learning.routing.feature_extraction import (
    DEFAULT_HASH_DIM,
    feature_dim,
    featurize,
)
from openjarvis.learning.routing.model_tiers import select_by_threshold

#: label=1 means "the strong model should be preferred for this query".
STRONG_LABEL = 1
WEAK_LABEL = 0


def _sigmoid(z: float) -> float:
    """Numerically stable logistic sigmoid."""
    if z >= 0:
        return 1.0 / (1.0 + math.exp(-z))
    ez = math.exp(z)
    return ez / (1.0 + ez)


def _clamp01(value: float) -> float:
    return max(0.0, min(1.0, float(value)))


def _fit_simple_logistic(
    X: List[List[float]],
    y: List[int],
    *,
    dim: int,
    learning_rate: float = 0.5,
    epochs: int = 300,
    l2: float = 1e-3,
    init_weights: Optional[List[float]] = None,
    init_bias: float = 0.0,
) -> Tuple[List[float], float]:
    """Batch gradient descent logistic regression. Pure stdlib, deterministic.

    Pass *init_weights*/*init_bias* (e.g. a previously trained classifier's
    weights) to warm-start instead of starting from zero — this is what
    powers incremental fine-tuning in ``training.py``.
    """
    weights = list(init_weights) if init_weights is not None else [0.0] * dim
    bias = init_bias
    n = len(X)
    if n == 0:
        return weights, bias
    for _epoch in range(epochs):
        grad_w = [0.0] * dim
        grad_b = 0.0
        for xi, yi in zip(X, y):
            z = sum(w * x for w, x in zip(weights, xi)) + bias
            error = _sigmoid(z) - yi
            for j, x in enumerate(xi):
                grad_w[j] += error * x
            grad_b += error
        for j in range(dim):
            grad_w[j] = grad_w[j] / n + l2 * weights[j]
            weights[j] -= learning_rate * grad_w[j]
        bias -= learning_rate * (grad_b / n)
    return weights, bias


def _fit_sklearn_logistic(
    X: List[List[float]], y: List[int], **kwargs: Any
) -> Tuple[List[float], float]:
    """Train with scikit-learn, exported to the same linear (w, b) form."""
    try:
        from sklearn.linear_model import LogisticRegression
    except ImportError as exc:  # pragma: no cover - exercised only without extra
        raise ImportError(
            "The 'sklearn' backend requires scikit-learn. Install it with "
            "pip install openjarvis[learning-router-classifier]"
        ) from exc

    kwargs.setdefault("max_iter", 1000)
    model = LogisticRegression(**kwargs)
    model.fit(X, y)
    weights = [float(w) for w in model.coef_[0]]
    bias = float(model.intercept_[0])
    return weights, bias


_BACKENDS = {
    "simple": _fit_simple_logistic,
    "sklearn": _fit_sklearn_logistic,
}


@dataclass
class RouteClassifier:
    """Linear P(strong model wins) classifier over :func:`featurize` vectors."""

    hash_dim: int = DEFAULT_HASH_DIM
    weights: List[float] = field(default_factory=list)
    bias: float = 0.0
    trained_backend: str = "untrained"
    metadata: Dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        dim = feature_dim(self.hash_dim)
        if not self.weights:
            self.weights = [0.0] * dim
        elif len(self.weights) != dim:
            raise ValueError(
                f"weights length {len(self.weights)} does not match "
                f"feature_dim(hash_dim={self.hash_dim}) = {dim}"
            )

    @property
    def dim(self) -> int:
        return feature_dim(self.hash_dim)

    @property
    def is_trained(self) -> bool:
        return self.trained_backend != "untrained"

    def predict_proba(self, query: str) -> float:
        """Return P(strong model wins) in [0, 1] for *query*.

        Returns 0.5 (maximally uncertain) if the classifier hasn't been
        trained yet — callers should treat that as "defer to a fallback",
        not as a confident 50/50 routing signal.
        """
        x = featurize(query, hash_dim=self.hash_dim)
        z = sum(w * xi for w, xi in zip(self.weights, x)) + self.bias
        return _sigmoid(z)

    def fit(
        self,
        examples: Sequence[Tuple[str, int]],
        *,
        backend: str = "simple",
        warm_start: bool = False,
        **backend_kwargs: Any,
    ) -> Dict[str, Any]:
        """Train on (query, label) pairs; label=1 means "prefer the strong model".

        Requires both labels present. Raises ``ValueError`` on empty/single-
        class data rather than silently producing a degenerate classifier.

        With ``warm_start=True`` (only meaningful for ``backend="simple"``
        on an already-trained classifier), gradient descent continues from
        the current weights instead of zero — incremental fine-tuning
        rather than a full retrain from scratch.
        """
        if not examples:
            raise ValueError("cannot fit RouteClassifier on an empty dataset")
        labels = {int(lbl) for _, lbl in examples}
        if not labels <= {WEAK_LABEL, STRONG_LABEL}:
            raise ValueError(f"labels must be 0 or 1, got {sorted(labels)}")
        if len(labels) < 2:
            raise ValueError(
                "fit() needs both classes represented (some weak-preferred "
                "and some strong-preferred examples) to learn a boundary"
            )
        if backend not in _BACKENDS:
            raise ValueError(
                f"unknown backend {backend!r}; choose from {sorted(_BACKENDS)}"
            )

        X = [featurize(q, hash_dim=self.hash_dim) for q, _ in examples]
        y = [int(lbl) for _, lbl in examples]
        if backend == "simple":
            if warm_start and self.is_trained:
                backend_kwargs.setdefault("init_weights", list(self.weights))
                backend_kwargs.setdefault("init_bias", self.bias)
            weights, bias = _fit_simple_logistic(X, y, dim=self.dim, **backend_kwargs)
        else:
            weights, bias = _fit_sklearn_logistic(X, y, **backend_kwargs)

        self.weights = weights
        self.bias = bias
        self.trained_backend = backend
        positive_rate = sum(y) / len(y)
        self.metadata = {
            **self.metadata,
            "n_examples": len(examples),
            "positive_rate": positive_rate,
        }
        return {
            "backend": backend,
            "n_examples": len(examples),
            "positive_rate": positive_rate,
        }

    def to_dict(self) -> Dict[str, Any]:
        return {
            "hash_dim": self.hash_dim,
            "weights": list(self.weights),
            "bias": self.bias,
            "trained_backend": self.trained_backend,
            "metadata": dict(self.metadata),
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "RouteClassifier":
        return cls(
            hash_dim=int(data.get("hash_dim", DEFAULT_HASH_DIM)),
            weights=list(data.get("weights") or []),
            bias=float(data.get("bias", 0.0)),
            trained_backend=str(data.get("trained_backend", "untrained")),
            metadata=dict(data.get("metadata") or {}),
        )

    def save(self, path: str | Path) -> None:
        p = Path(path)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(self.to_dict(), indent=2), encoding="utf-8")

    @classmethod
    def load(cls, path: str | Path) -> "RouteClassifier":
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        return cls.from_dict(data)


class ClassifierRouterPolicy(RouterPolicy):
    """Routes between a strong and weak model using a trained classifier + cost dial.

    Mirrors RouteLLM's decision rule: route to the strong model iff
    ``classifier.predict_proba(query) >= cost_threshold``. Raising the
    threshold biases routing toward the weak/cheap model (lower cost, lower
    average quality); lowering it does the opposite.
    """

    def __init__(
        self,
        classifier: Optional[RouteClassifier] = None,
        *,
        available_models: Optional[List[str]] = None,
        cost_threshold: float = 0.5,
        default_model: str = "",
        fallback_model: str = "",
    ) -> None:
        self._classifier = classifier or RouteClassifier()
        self._available = available_models or []
        self._threshold = _clamp01(cost_threshold)
        self._default = default_model
        self._fallback = fallback_model

    @property
    def classifier(self) -> RouteClassifier:
        return self._classifier

    @property
    def cost_threshold(self) -> float:
        return self._threshold

    def set_cost_threshold(self, value: float) -> None:
        """Adjust the cost/quality dial at runtime. Clamped to [0, 1]."""
        self._threshold = _clamp01(value)

    @property
    def available_models(self) -> List[str]:
        return list(self._available)

    def predict_proba(self, query: str) -> float:
        """Expose the raw P(strong model wins) — useful for dashboards/tests."""
        return self._classifier.predict_proba(query)

    def select_model(self, context: RoutingContext) -> str:
        available = self._available or list(ModelRegistry.keys())
        if not available:
            return self._default or self._fallback or ""

        if not self._classifier.is_trained:
            # No trained weights yet: don't guess with a coin flip, fall
            # back to the same deterministic chain HeuristicRouter uses.
            if self._default and self._default in available:
                return self._default
            if self._fallback and self._fallback in available:
                return self._fallback
            return available[0]

        proba = self._classifier.predict_proba(context.query)
        return select_by_threshold(available, proba, self._threshold)


def ensure_registered() -> None:
    """Register ClassifierRouterPolicy as ``"classifier"`` if not already present."""
    if not RouterPolicyRegistry.contains("classifier"):
        RouterPolicyRegistry.register_value("classifier", ClassifierRouterPolicy)


ensure_registered()

__all__ = [
    "STRONG_LABEL",
    "WEAK_LABEL",
    "ClassifierRouterPolicy",
    "RouteClassifier",
    "ensure_registered",
]
