"""Tests for RouteClassifier and ClassifierRouterPolicy (Feature 1)."""

from __future__ import annotations

import pytest

from openjarvis.core.registry import ModelRegistry, RouterPolicyRegistry
from openjarvis.core.types import ModelSpec
from openjarvis.learning._stubs import RoutingContext
from openjarvis.learning.routing.classifier import (
    STRONG_LABEL,
    WEAK_LABEL,
    ClassifierRouterPolicy,
    RouteClassifier,
    ensure_registered,
)

_WEAK_EXAMPLES = [
    "Hi",
    "Hello there",
    "What time is it?",
    "Thanks!",
    "What is 2 + 2?",
    "Define entropy.",
]
_STRONG_EXAMPLES = [
    "Explain step by step how gradient descent works, then compare it to Newton's "
    "method and analyze the trade-offs.",
    "Write Python code to implement an LRU cache, then explain its time complexity "
    "and edge cases.",
    "Solve the integral of x^2 * sin(x) and show your derivation step by step.",
    "Analyze the pros and cons of microservices versus a monolith for a high-traffic "
    "API, then recommend one and justify why.",
    "Debug this function, explain why it fails, then rewrite it:\n"
    "```python\ndef compute_total():\n    pass\n```",
    "First research the history of TCP/IP, then summarize modern container "
    "orchestration, then compare the two and draw conclusions.",
]


def _labeled_dataset():
    return [(q, WEAK_LABEL) for q in _WEAK_EXAMPLES] + [
        (q, STRONG_LABEL) for q in _STRONG_EXAMPLES
    ]


class TestRouteClassifierUntrained:
    def test_default_weights_are_zero(self) -> None:
        clf = RouteClassifier(hash_dim=16)
        assert clf.weights == [0.0] * clf.dim
        assert clf.bias == 0.0

    def test_untrained_predicts_neutral(self) -> None:
        clf = RouteClassifier(hash_dim=16)
        assert clf.predict_proba("anything") == pytest.approx(0.5)

    def test_is_trained_false(self) -> None:
        clf = RouteClassifier()
        assert clf.is_trained is False

    def test_weights_dim_mismatch_raises(self) -> None:
        with pytest.raises(ValueError):
            RouteClassifier(hash_dim=16, weights=[0.0] * 5)


class TestRouteClassifierFit:
    def test_empty_dataset_raises(self) -> None:
        clf = RouteClassifier()
        with pytest.raises(ValueError):
            clf.fit([])

    def test_single_class_raises(self) -> None:
        clf = RouteClassifier()
        with pytest.raises(ValueError):
            clf.fit([("a", WEAK_LABEL), ("b", WEAK_LABEL)])

    def test_invalid_label_raises(self) -> None:
        clf = RouteClassifier()
        with pytest.raises(ValueError):
            clf.fit([("a", 0), ("b", 2)])

    def test_unknown_backend_raises(self) -> None:
        clf = RouteClassifier()
        with pytest.raises(ValueError):
            clf.fit(_labeled_dataset(), backend="nonexistent")

    def test_fit_marks_trained(self) -> None:
        clf = RouteClassifier(hash_dim=32)
        result = clf.fit(_labeled_dataset(), epochs=100)
        assert clf.is_trained
        assert result["backend"] == "simple"
        assert result["n_examples"] == len(_labeled_dataset())

    def test_fit_learns_separable_boundary(self) -> None:
        clf = RouteClassifier(hash_dim=32)
        clf.fit(_labeled_dataset(), epochs=200, learning_rate=0.5)

        for q in _WEAK_EXAMPLES:
            assert clf.predict_proba(q) < 0.5, q
        for q in _STRONG_EXAMPLES:
            assert clf.predict_proba(q) > 0.5, q

    def test_warm_start_continues_from_current_weights(self) -> None:
        clf = RouteClassifier(hash_dim=32)
        clf.fit(_labeled_dataset(), epochs=50)
        weights_after_first_fit = list(clf.weights)

        clf.fit(_labeled_dataset(), epochs=50, warm_start=True)
        # Warm-started weights should differ from a plain re-fit's starting
        # point (zero) having converged further, not reset to zero.
        assert clf.weights != [0.0] * clf.dim
        assert clf.weights != weights_after_first_fit


class TestRouteClassifierSklearnBackend:
    def test_sklearn_backend_matches_simple_backend_shape(self) -> None:
        sklearn = pytest.importorskip("sklearn")
        del sklearn
        clf = RouteClassifier(hash_dim=32)
        result = clf.fit(_labeled_dataset(), backend="sklearn")
        assert result["backend"] == "sklearn"
        assert len(clf.weights) == clf.dim
        # Loading back requires no sklearn dependency at all (plain floats).
        reloaded = RouteClassifier.from_dict(clf.to_dict())
        assert reloaded.predict_proba("Hi") == pytest.approx(clf.predict_proba("Hi"))

    def test_sklearn_backend_missing_raises_helpful_error(self, monkeypatch) -> None:
        import builtins

        real_import = builtins.__import__

        def _fake_import(name, *args, **kwargs):
            if name == "sklearn.linear_model":
                raise ImportError("no sklearn")
            return real_import(name, *args, **kwargs)

        monkeypatch.setattr(builtins, "__import__", _fake_import)
        clf = RouteClassifier(hash_dim=16)
        with pytest.raises(ImportError, match="learning-router-classifier"):
            clf.fit(_labeled_dataset(), backend="sklearn")


class TestRouteClassifierPersistence:
    def test_to_dict_from_dict_round_trip(self) -> None:
        clf = RouteClassifier(hash_dim=32)
        clf.fit(_labeled_dataset(), epochs=50)
        restored = RouteClassifier.from_dict(clf.to_dict())
        assert restored.weights == clf.weights
        assert restored.bias == clf.bias
        assert restored.trained_backend == clf.trained_backend
        assert restored.predict_proba("Hi") == clf.predict_proba("Hi")

    def test_save_load_round_trip(self, tmp_path) -> None:
        clf = RouteClassifier(hash_dim=32)
        clf.fit(_labeled_dataset(), epochs=50)
        path = tmp_path / "nested" / "classifier.json"
        clf.save(path)
        assert path.exists()

        loaded = RouteClassifier.load(path)
        assert loaded.predict_proba("Hi") == pytest.approx(clf.predict_proba("Hi"))
        assert loaded.is_trained


class TestClassifierRouterPolicyUntrained:
    def test_falls_back_to_default(self) -> None:
        policy = ClassifierRouterPolicy(available_models=["a", "b"], default_model="a")
        ctx = RoutingContext(query="anything")
        assert policy.select_model(ctx) == "a"

    def test_falls_back_to_fallback_when_default_missing(self) -> None:
        policy = ClassifierRouterPolicy(
            available_models=["b"], default_model="missing", fallback_model="b"
        )
        ctx = RoutingContext(query="anything")
        assert policy.select_model(ctx) == "b"

    def test_falls_back_to_first_available(self) -> None:
        policy = ClassifierRouterPolicy(available_models=["only-one"])
        ctx = RoutingContext(query="anything")
        assert policy.select_model(ctx) == "only-one"

    def test_no_available_models_returns_default(self) -> None:
        policy = ClassifierRouterPolicy(default_model="d", fallback_model="f")
        ctx = RoutingContext(query="anything")
        assert policy.select_model(ctx) == "d"


class TestClassifierRouterPolicyTrained:
    def _register_models(self) -> None:
        ModelRegistry.register_value(
            "weak-model",
            ModelSpec(
                model_id="weak-model",
                name="weak",
                parameter_count_b=3.0,
                context_length=8192,
            ),
        )
        ModelRegistry.register_value(
            "strong-model",
            ModelSpec(
                model_id="strong-model",
                name="strong",
                parameter_count_b=70.0,
                context_length=8192,
            ),
        )

    def test_routes_simple_query_to_weak_model(self) -> None:
        self._register_models()
        clf = RouteClassifier(hash_dim=32)
        clf.fit(_labeled_dataset(), epochs=200)
        policy = ClassifierRouterPolicy(
            clf, available_models=["weak-model", "strong-model"], cost_threshold=0.5
        )
        ctx = RoutingContext(query="Hi there")
        assert policy.select_model(ctx) == "weak-model"

    def test_routes_complex_query_to_strong_model(self) -> None:
        self._register_models()
        clf = RouteClassifier(hash_dim=32)
        clf.fit(_labeled_dataset(), epochs=200)
        policy = ClassifierRouterPolicy(
            clf, available_models=["weak-model", "strong-model"], cost_threshold=0.5
        )
        ctx = RoutingContext(
            query=(
                "Explain step by step how gradient descent works, then compare it "
                "to Newton's method and analyze the trade-offs."
            )
        )
        assert policy.select_model(ctx) == "strong-model"

    def test_raising_threshold_biases_toward_weak(self) -> None:
        self._register_models()
        clf = RouteClassifier(hash_dim=32)
        clf.fit(_labeled_dataset(), epochs=200)
        policy = ClassifierRouterPolicy(
            clf, available_models=["weak-model", "strong-model"], cost_threshold=0.5
        )
        borderline_query = (
            "Give me a synonym for happy and explain your reasoning briefly"
        )
        policy.set_cost_threshold(0.0)
        low_threshold_choice = policy.select_model(
            RoutingContext(query=borderline_query)
        )
        policy.set_cost_threshold(1.0)
        high_threshold_choice = policy.select_model(
            RoutingContext(query=borderline_query)
        )
        assert low_threshold_choice == "strong-model"
        assert high_threshold_choice == "weak-model"

    def test_set_cost_threshold_clamps_to_unit_interval(self) -> None:
        policy = ClassifierRouterPolicy()
        policy.set_cost_threshold(5.0)
        assert policy.cost_threshold == 1.0
        policy.set_cost_threshold(-3.0)
        assert policy.cost_threshold == 0.0

    def test_predict_proba_exposes_raw_probability(self) -> None:
        clf = RouteClassifier(hash_dim=32)
        clf.fit(_labeled_dataset(), epochs=200)
        policy = ClassifierRouterPolicy(clf)
        proba = policy.predict_proba("Hi")
        assert 0.0 <= proba <= 1.0


class TestRegistration:
    def test_registered_as_classifier(self) -> None:
        ensure_registered()
        assert RouterPolicyRegistry.contains("classifier")

    def test_ensure_registered_idempotent(self) -> None:
        ensure_registered()
        ensure_registered()  # must not raise
        assert RouterPolicyRegistry.contains("classifier")

    def test_registry_create_instantiates_policy(self) -> None:
        ensure_registered()
        policy = RouterPolicyRegistry.create("classifier", available_models=["m"])
        assert isinstance(policy, ClassifierRouterPolicy)
