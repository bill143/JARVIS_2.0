"""
Custom LiteLLM logger that emits per-request records:
  - Alias requested (what the client asked for)
  - Actual model used (what LiteLLM routed to after fallbacks)
  - Input tokens / output tokens / total
  - Latency (ms)
  - Cost estimate (USD, computed from model_info in litellm_config.yaml)
  - Success / failure
  - Request ID (for correlation with Langfuse)

Emits to:
  - structlog (stdout, JSON lines)  - Railway picks this up automatically
  - Langfuse (via LiteLLM's native callback, NOT from this module)

This module sits alongside the Langfuse callback. Langfuse gets rich
trace data; this logger gets flat, grep-friendly records for ops.
"""
from __future__ import annotations

import json
import sys
import time
from typing import Any

from litellm.integrations.custom_logger import CustomLogger


class EchoLogger(CustomLogger):
    """Structured logger for ECHO proxy requests."""

    def _emit(self, record: dict[str, Any]) -> None:
        """Write a JSON line to stdout. Railway's log aggregator picks it up."""
        print(json.dumps(record, separators=(",", ":")), file=sys.stdout, flush=True)

    def log_success_event(
        self, kwargs: dict, response_obj: Any, start_time: float, end_time: float
    ) -> None:
        self._emit(self._build_record(kwargs, response_obj, start_time, end_time, ok=True))

    def log_failure_event(
        self, kwargs: dict, response_obj: Any, start_time: float, end_time: float
    ) -> None:
        self._emit(self._build_record(kwargs, response_obj, start_time, end_time, ok=False))

    async def async_log_success_event(
        self, kwargs: dict, response_obj: Any, start_time: float, end_time: float
    ) -> None:
        self.log_success_event(kwargs, response_obj, start_time, end_time)

    async def async_log_failure_event(
        self, kwargs: dict, response_obj: Any, start_time: float, end_time: float
    ) -> None:
        self.log_failure_event(kwargs, response_obj, start_time, end_time)

    def _build_record(
        self,
        kwargs: dict,
        response_obj: Any,
        start_time: float,
        end_time: float,
        *,
        ok: bool,
    ) -> dict[str, Any]:
        # Alias the client asked for (e.g. "compliance")
        requested_model = kwargs.get("model", "unknown")

        # Actual model used (e.g. "anthropic/claude-opus-4-7")
        # LiteLLM puts this in kwargs after fallback resolution
        actual_model = kwargs.get("litellm_params", {}).get("model", requested_model)

        # Token counts
        usage = getattr(response_obj, "usage", None) if response_obj else None
        prompt_tokens = getattr(usage, "prompt_tokens", 0) or 0
        completion_tokens = getattr(usage, "completion_tokens", 0) or 0
        total_tokens = getattr(usage, "total_tokens", 0) or (prompt_tokens + completion_tokens)

        # Cost - LiteLLM pre-computes from model_info in config
        response_cost = kwargs.get("response_cost")

        # Latency
        latency_ms = int((end_time - start_time) * 1000)

        # Request ID for Langfuse correlation
        request_id = kwargs.get("litellm_call_id") or kwargs.get("id")

        record = {
            "ts": time.time(),
            "service": "echo",
            "ok": ok,
            "alias": requested_model,
            "model": actual_model,
            "tokens_in": prompt_tokens,
            "tokens_out": completion_tokens,
            "tokens_total": total_tokens,
            "latency_ms": latency_ms,
            "cost_usd": response_cost,
            "request_id": request_id,
        }

        if not ok:
            exc = kwargs.get("exception")
            record["error"] = str(exc) if exc else "unknown"

        return record


# Singleton LiteLLM loads via callbacks: ["app.logging_middleware.echo_logger"]
echo_logger = EchoLogger()