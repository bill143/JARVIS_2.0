"""Tests for SimilarityRouterPolicy (Feature 3 — third swappable strategy)."""

from __future__ import annotations

from openjarvis.core.registry import RouterPolicyRegistry
from openjarvis.learning._stubs import RoutingContext
from openjarvis.learning.routing.similarity_router import (
    Exemplar,
    SimilarityRouterPolicy,
    ensure_registered,
)

_WEAK_EXEMPLARS = [
    ("Hi there, how are you?", "small-model"),
    ("What time is it right now?", "small-model"),
    ("Thanks so much for your help!", "small-model"),
    ("What is the capital of France?", "small-model"),
]
_STRONG_EXEMPLARS = [
    (
        "Explain step by step how TCP congestion control works, then compare "
        "it to UDP and analyze the trade-offs",
        "big-model",
    ),
    (
        "Write Python code to implement an LRU cache, then explain its time "
        "complexity and edge cases",
        "big-model",
    ),
    (
        "Debug this recursive function, explain why it fails, then rewrite it",
        "big-model",
    ),
    (
        "Analyze the pros and cons of microservices versus a monolith for a "
        "high-traffic API",
        "big-model",
    ),
]


def _seeded_policy(**kwargs) -> SimilarityRouterPolicy:
    policy = SimilarityRouterPolicy(
        available_models=["small-model", "big-model"], **kwargs
    )
    policy.add_exemplars(_WEAK_EXEMPLARS + _STRONG_EXEMPLARS)
    return policy


class TestExemplarManagement:
    def test_add_exemplar_stores_vector(self) -> None:
        policy = SimilarityRouterPolicy()
        policy.add_exemplar("hello", "model-a")
        assert len(policy.exemplars) == 1
        ex = policy.exemplars[0]
        assert isinstance(ex, Exemplar)
        assert ex.query == "hello"
        assert ex.model == "model-a"
        assert len(ex.vector) > 0

    def test_add_exemplars_bulk(self) -> None:
        policy = SimilarityRouterPolicy()
        policy.add_exemplars([("a", "m1"), ("b", "m2")])
        assert len(policy.exemplars) == 2

    def test_clear_exemplars(self) -> None:
        policy = SimilarityRouterPolicy()
        policy.add_exemplar("hello", "model-a")
        policy.clear_exemplars()
        assert policy.exemplars == []


class TestFallbackNoExemplars:
    def test_falls_back_to_default(self) -> None:
        policy = SimilarityRouterPolicy(available_models=["a", "b"], default_model="a")
        assert policy.select_model(RoutingContext(query="anything")) == "a"

    def test_falls_back_to_fallback(self) -> None:
        policy = SimilarityRouterPolicy(
            available_models=["b"], default_model="missing", fallback_model="b"
        )
        assert policy.select_model(RoutingContext(query="anything")) == "b"

    def test_falls_back_to_first_available(self) -> None:
        policy = SimilarityRouterPolicy(available_models=["only"])
        assert policy.select_model(RoutingContext(query="anything")) == "only"


class TestSelectModelWithExemplars:
    def test_routes_similar_query_to_weak_model(self) -> None:
        policy = _seeded_policy(k=3)
        ctx = RoutingContext(query="hey, how is it going?")
        assert policy.select_model(ctx) == "small-model"

    def test_routes_similar_query_to_strong_model(self) -> None:
        policy = _seeded_policy(k=3)
        ctx = RoutingContext(
            query="Explain step by step how gradient descent works and compare "
            "it to Newton's method"
        )
        assert policy.select_model(ctx) == "big-model"

    def test_unavailable_exemplar_models_excluded_from_voting(self) -> None:
        # Only "small-model" is available -- even a query near the "big-model"
        # exemplars must fall back rather than route to an unavailable model.
        policy = SimilarityRouterPolicy(
            available_models=["small-model"], default_model="small-model"
        )
        policy.add_exemplars(_WEAK_EXEMPLARS + _STRONG_EXEMPLARS)
        ctx = RoutingContext(
            query="Explain step by step how gradient descent works and compare "
            "it to Newton's method"
        )
        assert policy.select_model(ctx) == "small-model"

    def test_k_limits_neighbors_considered(self) -> None:
        policy = SimilarityRouterPolicy(
            available_models=["small-model", "big-model"], k=1
        )
        policy.add_exemplars(_WEAK_EXEMPLARS + _STRONG_EXEMPLARS)
        ctx = RoutingContext(query="hi, what's up?")
        # With k=1 only the single nearest exemplar votes.
        result = policy.select_model(ctx)
        assert result in ("small-model", "big-model")

    def test_min_similarity_gate_excludes_noise(self) -> None:
        # A very high min_similarity should reject every exemplar as "too far",
        # falling back rather than trusting a spurious low-similarity match.
        policy = SimilarityRouterPolicy(
            available_models=["small-model", "big-model"],
            min_similarity=0.99,
            default_model="small-model",
        )
        policy.add_exemplars(_WEAK_EXEMPLARS + _STRONG_EXEMPLARS)
        ctx = RoutingContext(query="hey, how is it going?")
        assert policy.select_model(ctx) == "small-model"

    def test_k_clamped_to_at_least_one(self) -> None:
        policy = SimilarityRouterPolicy(k=0)
        assert policy._k == 1  # noqa: SLF001 -- verifying the clamp, not public API


class TestRegistration:
    def test_registered_as_similarity(self) -> None:
        ensure_registered()
        assert RouterPolicyRegistry.contains("similarity")

    def test_ensure_registered_idempotent(self) -> None:
        ensure_registered()
        ensure_registered()
        assert RouterPolicyRegistry.contains("similarity")

    def test_registry_create_instantiates_policy(self) -> None:
        ensure_registered()
        policy = RouterPolicyRegistry.create("similarity", available_models=["m"])
        assert isinstance(policy, SimilarityRouterPolicy)
