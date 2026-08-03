"""Tests for the learning.routing package factory (create_router_policy)."""

from __future__ import annotations

import pytest

from openjarvis.core.registry import RouterPolicyRegistry
from openjarvis.learning.routing import (
    ClassifierRouterPolicy,
    HeuristicRouter,
    LearnedRouterPolicy,
    SimilarityRouterPolicy,
    create_router_policy,
    ensure_all_registered,
)


class TestEnsureAllRegistered:
    def test_registers_all_four_strategies(self) -> None:
        ensure_all_registered()
        assert set(RouterPolicyRegistry.keys()) == {
            "heuristic",
            "learned",
            "classifier",
            "similarity",
        }

    def test_idempotent(self) -> None:
        ensure_all_registered()
        ensure_all_registered()  # must not raise
        assert RouterPolicyRegistry.contains("classifier")


class TestCreateRouterPolicy:
    def test_creates_heuristic(self) -> None:
        policy = create_router_policy("heuristic", default_model="m")
        assert isinstance(policy, HeuristicRouter)

    def test_creates_learned(self) -> None:
        policy = create_router_policy("learned", default_model="m")
        assert isinstance(policy, LearnedRouterPolicy)

    def test_creates_classifier(self) -> None:
        policy = create_router_policy("classifier", cost_threshold=0.7)
        assert isinstance(policy, ClassifierRouterPolicy)
        assert policy.cost_threshold == 0.7

    def test_creates_similarity(self) -> None:
        policy = create_router_policy("similarity", k=5)
        assert isinstance(policy, SimilarityRouterPolicy)

    def test_unknown_strategy_raises_key_error(self) -> None:
        with pytest.raises(KeyError):
            create_router_policy("does-not-exist")

    def test_works_even_after_registry_cleared(self) -> None:
        # Simulates the autouse _clean_registries fixture wiping state
        # between tests -- create_router_policy must re-register on demand.
        RouterPolicyRegistry.clear()
        assert not RouterPolicyRegistry.contains("classifier")
        policy = create_router_policy("classifier")
        assert isinstance(policy, ClassifierRouterPolicy)
