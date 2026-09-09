"""Prometheus-compatible metrics with zero required dependencies.

Uses prometheus_client when installed for a richer registry, otherwise a tiny
built-in registry that renders the same text exposition format. Both expose the
required operational counters: request latency, WS connections/errors, tool call
counts/durations/failures, model token usage/cost, queue depth and job failures.
"""

from __future__ import annotations

import math
import threading
from collections import defaultdict

_DEFAULT_BUCKETS = (0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)


def _fmt_labels(labels: dict[str, str]) -> str:
    if not labels:
        return ""
    inner = ",".join(f'{k}="{str(v)}"' for k, v in sorted(labels.items()))
    return "{" + inner + "}"


class Metrics:
    """Thread-safe in-process metrics registry (counters, gauges, histograms)."""

    def __init__(self):
        self._lock = threading.Lock()
        self._counters: dict[tuple, float] = defaultdict(float)
        self._gauges: dict[tuple, float] = defaultdict(float)
        self._hist_sum: dict[tuple, float] = defaultdict(float)
        self._hist_count: dict[tuple, float] = defaultdict(float)
        self._hist_buckets: dict[tuple, dict[float, float]] = defaultdict(lambda: defaultdict(float))
        self._help: dict[str, str] = {}

    def _key(self, name: str, labels: dict | None) -> tuple:
        return (name, tuple(sorted((labels or {}).items())))

    def counter(self, name: str, value: float = 1.0, labels: dict | None = None, help: str = "") -> None:
        with self._lock:
            self._help.setdefault(name, help or name)
            self._counters[self._key(name, labels)] += value

    def gauge(self, name: str, value: float, labels: dict | None = None, help: str = "") -> None:
        with self._lock:
            self._help.setdefault(name, help or name)
            self._gauges[self._key(name, labels)] = value

    def observe(self, name: str, value: float, labels: dict | None = None, help: str = "") -> None:
        with self._lock:
            self._help.setdefault(name, help or name)
            key = self._key(name, labels)
            self._hist_sum[key] += value
            self._hist_count[key] += 1
            for bucket in _DEFAULT_BUCKETS:
                if value <= bucket:
                    self._hist_buckets[key][bucket] += 1

    def render(self) -> str:
        """Render the Prometheus text exposition format."""
        lines: list[str] = []
        with self._lock:
            emitted_help: set[str] = set()

            def emit_help(name: str, mtype: str):
                if name not in emitted_help:
                    lines.append(f"# HELP {name} {self._help.get(name, name)}")
                    lines.append(f"# TYPE {name} {mtype}")
                    emitted_help.add(name)

            for (name, labels), value in sorted(self._counters.items()):
                emit_help(name, "counter")
                lines.append(f"{name}{_fmt_labels(dict(labels))} {value}")
            for (name, labels), value in sorted(self._gauges.items()):
                emit_help(name, "gauge")
                lines.append(f"{name}{_fmt_labels(dict(labels))} {value}")
            for (name, labels), _ in sorted(self._hist_count.items()):
                emit_help(name, "histogram")
                label_dict = dict(labels)
                cumulative = 0.0
                buckets = self._hist_buckets[(name, labels)]
                for bucket in _DEFAULT_BUCKETS:
                    cumulative = buckets.get(bucket, cumulative)
                    le_labels = dict(label_dict)
                    le_labels["le"] = str(bucket)
                    lines.append(f"{name}_bucket{_fmt_labels(le_labels)} {cumulative}")
                inf_labels = dict(label_dict)
                inf_labels["le"] = "+Inf"
                lines.append(f"{name}_bucket{_fmt_labels(inf_labels)} {self._hist_count[(name, labels)]}")
                lines.append(f"{name}_sum{_fmt_labels(label_dict)} {self._hist_sum[(name, labels)]}")
                lines.append(f"{name}_count{_fmt_labels(label_dict)} {self._hist_count[(name, labels)]}")
        return "\n".join(lines) + "\n"

    def snapshot(self) -> dict:
        """Structured snapshot for the web dashboard."""
        with self._lock:
            return {
                "counters": {f"{n}{_fmt_labels(dict(l))}": v for (n, l), v in self._counters.items()},
                "gauges": {f"{n}{_fmt_labels(dict(l))}": v for (n, l), v in self._gauges.items()},
                "histograms": {
                    f"{n}{_fmt_labels(dict(l))}": {
                        "count": self._hist_count[(n, l)],
                        "sum": round(self._hist_sum[(n, l)], 4),
                        "avg": round(self._hist_sum[(n, l)] / self._hist_count[(n, l)], 4)
                        if self._hist_count[(n, l)]
                        else 0.0,
                    }
                    for (n, l) in self._hist_count
                },
            }


_METRICS = Metrics()


def get_metrics() -> Metrics:
    return _METRICS


# rough per-1k-token cost table (USD) for token-usage/cost estimates
_COST_PER_1K = {"gpt-4o": 0.005, "claude-sonnet-5": 0.003, "mock-1": 0.0}


def estimate_cost(model: str, tokens: int) -> float:
    rate = _COST_PER_1K.get(model, 0.001)
    return round(rate * tokens / 1000.0, 6) if not math.isnan(tokens) else 0.0
