"""Tests for decoupled model tiering (Feature 4 — any model, local or cloud)."""

from __future__ import annotations

from openjarvis.core.registry import ModelRegistry
from openjarvis.core.types import ModelSpec
from openjarvis.learning.routing.model_tiers import (
    ModelTiers,
    capability_score,
    rank_models,
    select_by_threshold,
    tier_models,
)


def _register(model_id: str, **kwargs) -> ModelSpec:
    spec = ModelSpec(
        model_id=model_id,
        name=model_id,
        parameter_count_b=kwargs.pop("parameter_count_b", 0.0),
        context_length=kwargs.pop("context_length", 8192),
        **kwargs,
    )
    ModelRegistry.register_value(model_id, spec)
    return spec


class TestCapabilityScore:
    def test_dense_model_uses_parameter_count(self) -> None:
        spec = ModelSpec(
            model_id="m", name="m", parameter_count_b=7.0, context_length=8192
        )
        assert capability_score(spec) == 7.0

    def test_moe_model_uses_active_parameter_count(self) -> None:
        spec = ModelSpec(
            model_id="m",
            name="m",
            parameter_count_b=120.0,
            active_parameter_count_b=5.0,
            context_length=8192,
        )
        assert capability_score(spec) == 5.0

    def test_cloud_model_uses_output_pricing(self) -> None:
        spec = ModelSpec(
            model_id="gpt",
            name="gpt",
            parameter_count_b=0.0,
            context_length=128000,
            requires_api_key=True,
            metadata={"pricing_output": 10.0},
        )
        assert capability_score(spec) == 10.0

    def test_unknown_model_scores_zero(self) -> None:
        spec = ModelSpec(
            model_id="mystery", name="mystery", parameter_count_b=0.0, context_length=0
        )
        assert capability_score(spec) == 0.0

    def test_zero_pricing_falls_back_to_zero(self) -> None:
        spec = ModelSpec(
            model_id="free",
            name="free",
            parameter_count_b=0.0,
            context_length=1000,
            metadata={"pricing_output": 0.0},
        )
        assert capability_score(spec) == 0.0


class TestRankModels:
    def test_ranks_weakest_to_strongest(self) -> None:
        _register("small", parameter_count_b=3.0)
        _register("large", parameter_count_b=70.0)
        _register("medium", parameter_count_b=14.0)
        assert rank_models(["large", "small", "medium"]) == ["small", "medium", "large"]

    def test_unregistered_keys_appended_at_end(self) -> None:
        _register("known", parameter_count_b=7.0)
        ranked = rank_models(["known", "unknown-model"])
        assert ranked == ["known", "unknown-model"]

    def test_all_unregistered_returns_input_order(self) -> None:
        assert rank_models(["a", "b", "c"]) == ["a", "b", "c"]

    def test_empty_input(self) -> None:
        assert rank_models([]) == []

    def test_mixes_local_and_cloud_by_capability(self) -> None:
        _register("local-small", parameter_count_b=3.0)
        _register("local-large", parameter_count_b=70.0)
        _register(
            "cloud-cheap",
            parameter_count_b=0.0,
            requires_api_key=True,
            metadata={"pricing_output": 0.6},
        )
        _register(
            "cloud-frontier",
            parameter_count_b=0.0,
            requires_api_key=True,
            metadata={"pricing_output": 75.0},
        )
        ranked = rank_models(
            ["local-large", "cloud-frontier", "local-small", "cloud-cheap"]
        )
        assert ranked == ["cloud-cheap", "local-small", "local-large", "cloud-frontier"]


class TestTierModels:
    def test_two_models_splits_weak_strong(self) -> None:
        _register("weak", parameter_count_b=3.0)
        _register("strong", parameter_count_b=70.0)
        tiers = tier_models(["weak", "strong"])
        assert tiers == ModelTiers(
            weak="weak", strong="strong", ranked=["weak", "strong"]
        )

    def test_single_model_returns_none(self) -> None:
        _register("only", parameter_count_b=7.0)
        assert tier_models(["only"]) is None

    def test_empty_returns_none(self) -> None:
        assert tier_models([]) is None


class TestSelectByThreshold:
    def test_high_probability_routes_strong(self) -> None:
        _register("weak", parameter_count_b=3.0)
        _register("strong", parameter_count_b=70.0)
        assert select_by_threshold(["weak", "strong"], 0.9, 0.5) == "strong"

    def test_low_probability_routes_weak(self) -> None:
        _register("weak", parameter_count_b=3.0)
        _register("strong", parameter_count_b=70.0)
        assert select_by_threshold(["weak", "strong"], 0.1, 0.5) == "weak"

    def test_probability_equal_to_threshold_routes_strong(self) -> None:
        _register("weak", parameter_count_b=3.0)
        _register("strong", parameter_count_b=70.0)
        assert select_by_threshold(["weak", "strong"], 0.5, 0.5) == "strong"

    def test_single_model_available_returns_it_regardless_of_probability(self) -> None:
        _register("only", parameter_count_b=7.0)
        assert select_by_threshold(["only"], 0.99, 0.5) == "only"
        assert select_by_threshold(["only"], 0.01, 0.5) == "only"

    def test_empty_available_returns_empty_string(self) -> None:
        assert select_by_threshold([], 0.5, 0.5) == ""
