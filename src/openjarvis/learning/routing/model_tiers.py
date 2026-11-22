"""Decoupled model tiering — buckets ANY registered model into strong/weak tiers.

Reads only ``ModelSpec`` metadata already present in ``ModelRegistry``
(parameter count, active/MoE parameter count, cloud pricing) — no model
names, providers, or engines are hardcoded. This is what lets
``ClassifierRouterPolicy`` and ``SimilarityRouterPolicy`` route across local
GGUF models, self-hosted MoE models, and paid cloud APIs interchangeably,
including models registered at runtime via
``intelligence.model_catalog.merge_discovered_models``.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional, Sequence

from openjarvis.core.registry import ModelRegistry
from openjarvis.core.types import ModelSpec


def capability_score(spec: ModelSpec) -> float:
    """Rough, provider-agnostic capability proxy (higher = more capable).

    - Local/open MoE models: active parameter count (the actual compute
      per token, which correlates with quality better than total params).
    - Local/open dense models: total parameter count.
    - Cloud/proprietary models (``parameter_count_b == 0``): output-token
      price as a proxy — frontier proprietary APIs are priced roughly in
      line with their capability tier, and pricing is the only capability
      signal available for closed-weight models.
    - Unknown models (no registry entry, no pricing): 0.0, sorted weakest.
    """
    if spec.active_parameter_count_b:
        return float(spec.active_parameter_count_b)
    if spec.parameter_count_b:
        return float(spec.parameter_count_b)
    price = spec.metadata.get("pricing_output")
    if isinstance(price, (int, float)) and price > 0:
        return float(price)
    return 0.0


def _resolve_specs(available: Sequence[str]) -> List[ModelSpec]:
    specs: List[ModelSpec] = []
    for key in available:
        try:
            spec = ModelRegistry.get(key)
        except KeyError:
            continue
        if isinstance(spec, ModelSpec):
            specs.append(spec)
    return specs


@dataclass(frozen=True)
class ModelTiers:
    """Weakest/strongest model in an available set, plus the full ranking."""

    weak: str
    strong: str
    ranked: List[str]  # weakest -> strongest


def rank_models(available: Sequence[str]) -> List[str]:
    """Rank *available* model keys from weakest to strongest capability.

    Keys with no ``ModelRegistry`` entry (e.g. not yet discovered) are kept
    at the end of the ranking rather than dropped, so callers always get
    back every key they passed in.
    """
    specs = _resolve_specs(available)
    if not specs:
        return list(available)
    ranked_specs = sorted(specs, key=capability_score)
    ranked = [s.model_id for s in ranked_specs]
    known = {s.model_id for s in ranked_specs}
    missing = [k for k in available if k not in known]
    return ranked + missing


def tier_models(available: Sequence[str]) -> Optional[ModelTiers]:
    """Bucket *available* into a (weak, strong) pair for binary routing.

    Returns ``None`` if fewer than two models are available.
    """
    ranked = rank_models(available)
    if len(ranked) < 2:
        return None
    return ModelTiers(weak=ranked[0], strong=ranked[-1], ranked=ranked)


def select_by_threshold(
    available: Sequence[str],
    strong_win_probability: float,
    threshold: float,
) -> str:
    """RouteLLM-style decision rule: route to the strong model iff P >= threshold.

    Falls back to the single available model (or the strong side of the
    tiering) when fewer than two models are available, and to an empty
    string only when *available* itself is empty.
    """
    if not available:
        return ""
    tiers = tier_models(available)
    if tiers is None:
        return available[0]
    return tiers.strong if strong_win_probability >= threshold else tiers.weak


__all__ = [
    "ModelTiers",
    "capability_score",
    "rank_models",
    "select_by_threshold",
    "tier_models",
]
