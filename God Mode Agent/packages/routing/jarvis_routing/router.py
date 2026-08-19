"""Intelligent model routing by task type, latency target, risk, and budget.

Chooses a provider/model from a fallback matrix; in compliance mode restricts
sensitive tasks to approved providers; enforces per-tenant daily budget; and
supports dynamic context compression. Deterministic and dependency-free.
"""

from __future__ import annotations

from dataclasses import dataclass, field

# Fallback matrix by task type. First entry is preferred; the rest are fallbacks.
ROUTE_MATRIX = {
    "chat":      [("openai", "gpt-4o"), ("anthropic", "claude-sonnet-5"), ("mock", "mock-1")],
    "reasoning": [("anthropic", "claude-sonnet-5"), ("openai", "gpt-4o"), ("mock", "mock-1")],
    "cheap":     [("openai", "gpt-4o-mini"), ("anthropic", "claude-haiku"), ("mock", "mock-1")],
    "fast":      [("openai", "gpt-4o-mini"), ("mock", "mock-1")],
    "sensitive": [("anthropic", "claude-sonnet-5"), ("mock", "mock-1")],
}

# approximate latency (ms) by model, for latency-target routing
MODEL_LATENCY_MS = {"gpt-4o": 1800, "gpt-4o-mini": 700, "claude-sonnet-5": 1600,
                    "claude-haiku": 600, "mock-1": 5}


@dataclass
class RouteDecision:
    provider: str
    model: str
    task_type: str
    reason: str
    fallbacks: list[tuple] = field(default_factory=list)
    downgraded: bool = False
    compressed: bool = False

    def as_dict(self) -> dict:
        return {"provider": self.provider, "model": self.model, "task_type": self.task_type,
                "reason": self.reason, "downgraded": self.downgraded, "compressed": self.compressed,
                "fallbacks": [f"{p}/{m}" for p, m in self.fallbacks]}


class ModelRouter:
    def __init__(self, settings, budget_manager=None):
        self.settings = settings
        self.budget = budget_manager

    def classify_task(self, message: str, risk: str = "low") -> str:
        text = (message or "").lower()
        if risk == "high":
            return "sensitive"
        if any(w in text for w in ("prove", "analyze", "reason", "explain why", "step by step")):
            return "reasoning"
        if len(text) < 40:
            return "fast"
        return "chat"

    def route(self, *, message: str, tenant: str, risk: str = "low",
              latency_target_ms: int | None = None) -> RouteDecision:
        latency_target_ms = latency_target_ms or self.settings.routing_latency_target_ms
        task_type = self.classify_task(message, risk)
        matrix = list(ROUTE_MATRIX.get(task_type, ROUTE_MATRIX["chat"]))

        # Compliance mode: restrict sensitive tasks to approved providers only.
        if self.settings.compliance_mode and (risk == "high" or task_type == "sensitive"):
            matrix = [(p, m) for p, m in matrix if p in self.settings.approved_providers]
            if not matrix:
                matrix = [("mock", "mock-1")]

        provider, model = matrix[0]
        reason = f"task '{task_type}', risk '{risk}'"
        downgraded = False

        # Latency target: pick the first model meeting the target if the preferred is too slow.
        if MODEL_LATENCY_MS.get(model, 9999) > latency_target_ms:
            for p, m in matrix:
                if MODEL_LATENCY_MS.get(m, 9999) <= latency_target_ms:
                    provider, model, downgraded = p, m, True
                    reason += f"; downgraded for latency target {latency_target_ms}ms"
                    break

        # Budget: if over budget, force the cheapest (mock) tier.
        if self.budget:
            allowed, _status = self.budget.allow_spend(tenant, 0.01)
            if not allowed:
                provider, model, downgraded = "mock", "mock-1", True
                reason += "; over daily budget -> mock tier"

        # Dynamic context compression for long inputs.
        compressed = len(message or "") > 4000

        return RouteDecision(provider=provider, model=model, task_type=task_type, reason=reason,
                             fallbacks=matrix[1:], downgraded=downgraded, compressed=compressed)

    @staticmethod
    def compress_context(text: str, max_chars: int = 4000) -> str:
        """Naive dynamic context compression: keep head + tail with an elision marker."""
        if len(text) <= max_chars:
            return text
        head = text[: max_chars // 2]
        tail = text[-max_chars // 2:]
        return f"{head}\n…[context compressed: {len(text) - max_chars} chars elided]…\n{tail}"
