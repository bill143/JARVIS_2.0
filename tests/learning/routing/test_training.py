"""Tests for the recalibration/retraining pipeline (Feature 5)."""

from __future__ import annotations

import time

import pytest

from openjarvis.core.registry import ModelRegistry
from openjarvis.core.types import ModelSpec, StepType, Trace, TraceStep
from openjarvis.learning.routing.classifier import (
    STRONG_LABEL,
    WEAK_LABEL,
    RouteClassifier,
)
from openjarvis.learning.routing.training import (
    DEFAULT_SUCCESS_FLOOR,
    bootstrap_dataset,
    build_dataset_from_traces,
    fine_tune,
    retrain,
)
from openjarvis.traces.store import TraceStore


def _make_trace(
    query: str = "test",
    model: str = "qwen3:8b",
    outcome: str | None = "success",
    feedback: float | None = 0.8,
) -> Trace:
    now = time.time()
    return Trace(
        query=query,
        agent="orchestrator",
        model=model,
        engine="ollama",
        result="result",
        outcome=outcome,
        feedback=feedback,
        started_at=now,
        ended_at=now + 0.5,
        total_tokens=100,
        total_latency_seconds=0.5,
        steps=[
            TraceStep(
                step_type=StepType.GENERATE,
                timestamp=now,
                duration_seconds=0.5,
                output={"tokens": 100},
            ),
        ],
    )


def _register_tiered_models() -> None:
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


class TestBootstrapDataset:
    def test_nonempty(self) -> None:
        assert len(bootstrap_dataset()) > 0

    def test_roughly_balanced(self) -> None:
        dataset = bootstrap_dataset()
        positive_rate = sum(label for _, label in dataset) / len(dataset)
        assert abs(positive_rate - 0.5) < 0.1

    def test_deterministic(self) -> None:
        assert bootstrap_dataset() == bootstrap_dataset()

    def test_both_labels_present(self) -> None:
        labels = {label for _, label in bootstrap_dataset()}
        assert labels == {WEAK_LABEL, STRONG_LABEL}

    def test_more_variants_yields_more_examples(self) -> None:
        small = bootstrap_dataset(variants_per_template=2)
        large = bootstrap_dataset(variants_per_template=6)
        assert len(large) >= len(small)


class TestBuildDatasetFromTraces:
    def test_empty_store_returns_empty(self, tmp_path) -> None:
        store = TraceStore(tmp_path / "t.db")
        assert build_dataset_from_traces(store) == []
        store.close()

    def test_single_distinct_model_returns_empty(self, tmp_path) -> None:
        store = TraceStore(tmp_path / "t.db")
        store.save(_make_trace(model="only-model"))
        assert build_dataset_from_traces(store) == []
        store.close()

    def test_weak_model_success_labeled_weak(self, tmp_path) -> None:
        _register_tiered_models()
        store = TraceStore(tmp_path / "t.db")
        store.save(
            _make_trace(query="q1", model="weak-model", outcome="success", feedback=0.9)
        )
        store.save(
            _make_trace(
                query="q2", model="strong-model", outcome="success", feedback=0.9
            )
        )
        assert build_dataset_from_traces(store) == [("q1", WEAK_LABEL)]
        store.close()

    def test_weak_model_failure_labeled_strong(self, tmp_path) -> None:
        _register_tiered_models()
        store = TraceStore(tmp_path / "t.db")
        store.save(
            _make_trace(query="q1", model="weak-model", outcome="failure", feedback=0.1)
        )
        store.save(
            _make_trace(
                query="q2", model="strong-model", outcome="success", feedback=0.9
            )
        )
        assert build_dataset_from_traces(store) == [("q1", STRONG_LABEL)]
        store.close()

    def test_success_floor_boundary(self, tmp_path) -> None:
        _register_tiered_models()
        store = TraceStore(tmp_path / "t.db")
        # reward = 0.6*1(success) + 0.4*0.5(feedback) = 0.8 >= default floor 0.6
        store.save(
            _make_trace(query="q1", model="weak-model", outcome="success", feedback=0.5)
        )
        store.save(
            _make_trace(
                query="q2", model="strong-model", outcome="success", feedback=0.9
            )
        )
        dataset = build_dataset_from_traces(store, success_floor=DEFAULT_SUCCESS_FLOOR)
        assert dataset == [("q1", WEAK_LABEL)]
        store.close()

    def test_stricter_success_floor_flips_label(self, tmp_path) -> None:
        _register_tiered_models()
        store = TraceStore(tmp_path / "t.db")
        store.save(
            _make_trace(query="q1", model="weak-model", outcome="success", feedback=0.5)
        )
        store.save(
            _make_trace(
                query="q2", model="strong-model", outcome="success", feedback=0.9
            )
        )
        dataset = build_dataset_from_traces(store, success_floor=0.9)
        assert dataset == [("q1", STRONG_LABEL)]
        store.close()

    def test_empty_query_skipped(self, tmp_path) -> None:
        _register_tiered_models()
        store = TraceStore(tmp_path / "t.db")
        store.save(_make_trace(query="", model="weak-model", outcome="success"))
        store.save(_make_trace(query="q2", model="strong-model", outcome="success"))
        assert build_dataset_from_traces(store) == []
        store.close()


class TestRetrain:
    def test_raises_when_not_enough_data(self) -> None:
        with pytest.raises(ValueError):
            retrain(include_bootstrap=False, min_examples=4)

    def test_uses_bootstrap_when_no_trace_store(self) -> None:
        result = retrain(include_bootstrap=True, min_examples=4)
        assert result["bootstrap_examples"] > 0
        assert result["trace_examples"] == 0
        assert result["classifier"].is_trained

    def test_uses_trace_data_when_sufficient(self, tmp_path) -> None:
        _register_tiered_models()
        store = TraceStore(tmp_path / "t.db")
        for i in range(3):
            store.save(
                _make_trace(
                    query=f"weak success {i}",
                    model="weak-model",
                    outcome="success",
                    feedback=0.9,
                )
            )
        for i in range(3):
            store.save(
                _make_trace(
                    query=f"weak failure {i}",
                    model="weak-model",
                    outcome="failure",
                    feedback=0.1,
                )
            )
        store.save(
            _make_trace(query="strong ok", model="strong-model", outcome="success")
        )

        result = retrain(trace_store=store, include_bootstrap=False, min_examples=4)
        assert result["trace_examples"] == 6
        assert result["bootstrap_examples"] == 0
        store.close()

    def test_save_path_persists_classifier(self, tmp_path) -> None:
        save_path = tmp_path / "clf.json"
        result = retrain(include_bootstrap=True, min_examples=4, save_path=save_path)
        assert save_path.exists()
        assert result["saved_to"] == str(save_path)
        assert RouteClassifier.load(save_path).is_trained


class TestFineTune:
    def test_raises_on_untrained_classifier(self, tmp_path) -> None:
        store = TraceStore(tmp_path / "t.db")
        with pytest.raises(ValueError):
            fine_tune(RouteClassifier(), store)
        store.close()

    def test_raises_on_insufficient_new_data(self, tmp_path) -> None:
        clf = retrain(include_bootstrap=True, min_examples=4)["classifier"]
        store = TraceStore(tmp_path / "t.db")
        with pytest.raises(ValueError):
            fine_tune(clf, store, min_examples=1)
        store.close()

    def test_fine_tune_warm_starts_and_persists(self, tmp_path) -> None:
        _register_tiered_models()
        clf = retrain(include_bootstrap=True, min_examples=4)["classifier"]
        weights_before = list(clf.weights)

        store = TraceStore(tmp_path / "t.db")
        for i in range(3):
            store.save(
                _make_trace(
                    query=f"weak ok {i}",
                    model="weak-model",
                    outcome="success",
                    feedback=0.9,
                )
            )
        for i in range(3):
            store.save(
                _make_trace(
                    query=f"weak bad {i}",
                    model="weak-model",
                    outcome="failure",
                    feedback=0.1,
                )
            )
        store.save(
            _make_trace(query="strong ok", model="strong-model", outcome="success")
        )

        save_path = tmp_path / "clf.json"
        result = fine_tune(clf, store, save_path=save_path)

        assert result["total_examples"] == 6
        assert clf.weights != weights_before
        assert save_path.exists()
        store.close()
