"""Recalibration / retraining pipeline for the classifier router.

Two labeled-data sources feed :func:`retrain`:

- **Real usage data** (:func:`build_dataset_from_traces`): derives
  ``(query, label)`` pairs from a ``TraceStore`` of actual production
  traces. Traces record a single model's outcome per query, not a paired
  strong-vs-weak comparison the way RouteLLM's preference data does (see
  ``RESEARCH.md``), so this uses a documented proxy: a query routed to a
  *weak*-tier model that scored low reward is evidence the strong model
  should have been used (``STRONG_LABEL``); a query routed to a weak-tier
  model that scored well is evidence the weak model was already
  sufficient (``WEAK_LABEL``). Strong-tier-model traces are intentionally
  **not** used as labels — without a same-query weak-model comparison
  there's no reliable signal to extract from a strong model's outcome
  alone, and a wrong label is worse than no label.
- **Bootstrap data** (:func:`bootstrap_dataset`): a small, deterministic,
  heuristic-distilled corpus for cold start (no traces yet). Every label
  comes from ``complexity.score_complexity()`` — a rule-based scorer
  already in this codebase — via a median split, never from calling any
  LLM (paid or otherwise).

:func:`retrain` trains fresh (falling back to bootstrap data when trace
data is thin); :func:`fine_tune` warm-starts from an already-trained
classifier's weights to incrementally adapt to newly observed traces.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from openjarvis.core.types import Trace
from openjarvis.learning.routing.classifier import (
    STRONG_LABEL,
    WEAK_LABEL,
    RouteClassifier,
)
from openjarvis.learning.routing.model_tiers import rank_models

DEFAULT_SUCCESS_FLOOR = 0.6

# ---------------------------------------------------------------------------
# Real usage data
# ---------------------------------------------------------------------------


def _trace_reward(trace: Trace) -> float:
    """Same 0.6*outcome + 0.4*feedback composite ``LearnedRouterPolicy`` uses."""
    outcome_score = 1.0 if trace.outcome == "success" else 0.0
    feedback = trace.feedback if trace.feedback is not None else 0.5
    return 0.6 * outcome_score + 0.4 * feedback


def build_dataset_from_traces(
    trace_store: Any,
    *,
    success_floor: float = DEFAULT_SUCCESS_FLOOR,
    limit: int = 10_000,
) -> List[Tuple[str, int]]:
    """Derive labeled ``(query, label)`` pairs from real traces.

    Only traces whose model resolves into the weaker half of the models
    observed across the trace set are used — see the module docstring for
    why strong-tier traces are skipped. Returns an empty list when there
    are fewer than two distinct models in the trace history (nothing to
    rank into weak/strong tiers).
    """
    traces = trace_store.list_traces(limit=limit)
    if not traces:
        return []

    distinct_models = sorted({t.model for t in traces if t.model})
    if len(distinct_models) < 2:
        return []

    ranked = rank_models(distinct_models)
    midpoint = max(len(ranked) // 2, 1)
    weak_tier = set(ranked[:midpoint])

    dataset: List[Tuple[str, int]] = []
    for trace in traces:
        if not trace.query or trace.model not in weak_tier:
            continue
        reward = _trace_reward(trace)
        label = WEAK_LABEL if reward >= success_floor else STRONG_LABEL
        dataset.append((trace.query, label))
    return dataset


# ---------------------------------------------------------------------------
# Bootstrap (cold start) data
# ---------------------------------------------------------------------------

_WEAK_TEMPLATES = [
    "Hi",
    "Hello there",
    "What time is it?",
    "What's {a} + {b}?",
    "What is the capital of {place}?",
    "Define {term}.",
    "Translate 'hello' into {language}.",
    "Is {a} bigger than {b}?",
    "What day comes after {day}?",
    "Give me a synonym for {word}.",
    "Thanks, that helps!",
    "What color is the sky?",
]

_STRONG_TEMPLATES = [
    "Explain step by step how {topic} works, then compare it to {alt} "
    "and analyze the trade-offs.",
    "Write Python code to {task}, then explain its time complexity and edge cases.",
    "Solve the integral of {expr} and show your derivation step by step.",
    "Analyze the pros and cons of {a} versus {b} for {context}, "
    "then recommend one and justify why.",
    "Debug this function, explain why it fails, then rewrite it:\n"
    "```python\ndef {fn}():\n    pass\n```",
    "First research {topic1}, then summarize {topic2}, "
    "then compare the two and draw conclusions.",
    "Design a system architecture for {system}, considering scalability, "
    "cost, and failure modes.",
    "Prove that {claim}, showing each step of the reasoning.",
]

_FILL: Dict[str, List[str]] = {
    "a": ["3", "7", "12", "cats", "Python", "5"],
    "b": ["9", "2", "dogs", "Rust", "4", "20"],
    "place": ["France", "Japan", "Brazil", "Egypt", "Canada", "Peru"],
    "term": [
        "entropy",
        "recursion",
        "inflation",
        "photosynthesis",
        "latency",
        "quorum",
    ],
    "language": ["Spanish", "French", "German", "Japanese", "Italian", "Korean"],
    "day": ["Monday", "Friday", "Sunday", "Wednesday", "Tuesday", "Saturday"],
    "word": ["happy", "fast", "big", "quiet", "clever", "bright"],
    "topic": [
        "gradient descent",
        "TCP congestion control",
        "garbage collection",
        "quicksort",
        "consistent hashing",
        "raft consensus",
    ],
    "alt": [
        "simulated annealing",
        "UDP",
        "reference counting",
        "mergesort",
        "rendezvous hashing",
        "paxos",
    ],
    "task": [
        "merge two sorted lists",
        "detect a cycle in a linked list",
        "parse a CSV file",
        "implement an LRU cache",
        "rate-limit an API endpoint",
        "deduplicate a stream of events",
    ],
    "expr": [
        "x^2 * sin(x)",
        "1/(1+x^2)",
        "e^x * cos(x)",
        "x * ln(x)",
        "sqrt(1 - x^2)",
        "x^3 - 3x",
    ],
    "context": [
        "a high-traffic API",
        "a mobile app",
        "a data pipeline",
        "an embedded device",
        "a multi-tenant SaaS product",
        "a batch analytics job",
    ],
    "fn": [
        "compute_total",
        "parse_input",
        "merge_lists",
        "validate_request",
        "build_index",
        "flush_queue",
    ],
    "topic1": [
        "the history of TCP/IP",
        "the causes of the 2008 financial crisis",
        "the origins of the JVM garbage collector",
        "early distributed database design",
        "the evolution of HTTP",
        "the history of public-key cryptography",
    ],
    "topic2": [
        "modern container orchestration",
        "current monetary policy",
        "modern concurrent garbage collectors",
        "modern distributed consensus protocols",
        "HTTP/3 and QUIC",
        "post-quantum cryptography",
    ],
    "system": [
        "a real-time chat application",
        "a distributed job queue",
        "a recommendation engine",
        "a video transcoding pipeline",
        "an inventory management service",
        "a fraud-detection pipeline",
    ],
    "claim": [
        "the square root of 2 is irrational",
        "there are infinitely many primes",
        "every finite group of prime order is cyclic",
        "a binary tree with n nodes has at most n-1 edges",
        "the halting problem is undecidable",
        "quicksort has average-case O(n log n) time complexity",
    ],
}

_PLACEHOLDER_RE = re.compile(r"\{(\w+)\}")


def _fill_template(template: str, variant_index: int) -> str:
    """Deterministically fill a template's ``{placeholders}``.

    Selection is purely a function of *variant_index* (no ``random``/
    ``time`` dependency), so the corpus is identical on every call —
    required for reproducible bootstrap training.
    """

    def repl(match: "re.Match[str]") -> str:
        key = match.group(1)
        options = _FILL.get(key, [key])
        return options[variant_index % len(options)]

    return _PLACEHOLDER_RE.sub(repl, template)


def bootstrap_dataset(*, variants_per_template: int = 6) -> List[Tuple[str, int]]:
    """Deterministic, heuristic-distilled seed corpus for cold-start training.

    Labels come from a **rank-based median split** of
    ``score_complexity().score`` over the generated corpus: the bottom
    half by sorted position is ``WEAK_LABEL``, the top half is
    ``STRONG_LABEL``. Ranking by position rather than comparing against
    a raw threshold value matters here — this corpus has many
    near-duplicate short queries that tie on score, and a plain
    ``score >= median`` split lets a large tied block collapse almost
    everything into one class (observed empirically: an 82/18 split,
    which then biases the trained classifier toward one side). Splitting
    by rank guarantees an even ~50/50 class balance regardless of ties.
    No LLM of any kind is called to produce these labels.
    """
    from openjarvis.learning.routing.complexity import score_complexity

    texts: List[str] = []
    seen: set = set()
    for template in _WEAK_TEMPLATES + _STRONG_TEMPLATES:
        for i in range(variants_per_template):
            text = _fill_template(template, i)
            if text in seen:
                continue
            seen.add(text)
            texts.append(text)

    scores = [score_complexity(text).score for text in texts]
    order = sorted(range(len(texts)), key=lambda i: scores[i])
    half = len(order) // 2

    labels = [WEAK_LABEL] * len(texts)
    for rank, idx in enumerate(order):
        if rank >= half:
            labels[idx] = STRONG_LABEL

    return list(zip(texts, labels))


# ---------------------------------------------------------------------------
# Retraining / fine-tuning entry points
# ---------------------------------------------------------------------------


def retrain(
    classifier: Optional[RouteClassifier] = None,
    *,
    trace_store: Optional[Any] = None,
    include_bootstrap: bool = True,
    backend: str = "simple",
    success_floor: float = DEFAULT_SUCCESS_FLOOR,
    min_examples: int = 4,
    save_path: Optional[str | Path] = None,
    **backend_kwargs: Any,
) -> Dict[str, Any]:
    """Train (or fully retrain) a ``RouteClassifier``.

    Uses real trace data when *trace_store* is given; falls back to (or
    tops up with) :func:`bootstrap_dataset` when there's fewer than
    *min_examples* real examples and *include_bootstrap* is true. Raises
    ``ValueError`` rather than training on too little data.
    """
    dataset: List[Tuple[str, int]] = []
    trace_count = 0
    if trace_store is not None:
        trace_examples = build_dataset_from_traces(
            trace_store, success_floor=success_floor
        )
        dataset.extend(trace_examples)
        trace_count = len(trace_examples)

    bootstrap_count = 0
    if include_bootstrap and len(dataset) < min_examples:
        boot = bootstrap_dataset()
        dataset.extend(boot)
        bootstrap_count = len(boot)

    if len(dataset) < min_examples:
        raise ValueError(
            f"Not enough labeled examples to train ({len(dataset)} < {min_examples}); "
            "provide a TraceStore with more traces or enable include_bootstrap"
        )

    clf = classifier or RouteClassifier()
    fit_result = clf.fit(dataset, backend=backend, **backend_kwargs)

    if save_path is not None:
        clf.save(save_path)

    return {
        **fit_result,
        "trace_examples": trace_count,
        "bootstrap_examples": bootstrap_count,
        "total_examples": len(dataset),
        "saved_to": str(save_path) if save_path is not None else None,
        "classifier": clf,
    }


def fine_tune(
    classifier: RouteClassifier,
    trace_store: Any,
    *,
    success_floor: float = DEFAULT_SUCCESS_FLOOR,
    min_examples: int = 1,
    save_path: Optional[str | Path] = None,
    **backend_kwargs: Any,
) -> Dict[str, Any]:
    """Incrementally adapt an already-trained classifier to new trace data.

    Unlike :func:`retrain`, this never falls back to bootstrap data and
    always warm-starts from *classifier*'s current weights (see
    ``RouteClassifier.fit``) — for periodically nudging a live classifier
    as new traces arrive, not for the initial cold-start train.
    """
    if not classifier.is_trained:
        raise ValueError(
            "fine_tune() requires an already-trained classifier; "
            "use retrain() for cold start"
        )

    dataset = build_dataset_from_traces(trace_store, success_floor=success_floor)
    if len(dataset) < min_examples:
        raise ValueError(
            f"Not enough new trace examples to fine-tune "
            f"({len(dataset)} < {min_examples})"
        )

    fit_result = classifier.fit(
        dataset, backend="simple", warm_start=True, **backend_kwargs
    )

    if save_path is not None:
        classifier.save(save_path)

    return {
        **fit_result,
        "total_examples": len(dataset),
        "saved_to": str(save_path) if save_path is not None else None,
    }


__all__ = [
    "DEFAULT_SUCCESS_FLOOR",
    "bootstrap_dataset",
    "build_dataset_from_traces",
    "fine_tune",
    "retrain",
]
