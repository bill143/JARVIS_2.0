"""Provider failover router with retry+jitter, per-provider circuit breakers, metrics."""

from __future__ import annotations

from jarvis_adapters.anthropic_adapter import AnthropicAdapter
from jarvis_adapters.base import ModelAdapter, TransientProviderError
from jarvis_adapters.mock_adapter import MockAdapter
from jarvis_adapters.openai_adapter import OpenAIAdapter
from jarvis_reliability.circuit_breaker import CircuitRegistry
from jarvis_reliability.retry import retry_async
from jarvis_shared.config import Settings
from jarvis_shared.errors import AllProvidersFailed
from jarvis_shared.logging import get_logger, log_event
from jarvis_shared.schemas import Message, ModelResponse

logger = get_logger("jarvis.router")


class ProviderRouter:
    def __init__(
        self,
        adapters: list[ModelAdapter],
        enable_fallbacks: bool = True,
        max_retries: int = 2,
        base_delay: float = 0.25,
        metrics=None,
        breaker_threshold: int = 5,
        breaker_recovery: float = 15.0,
    ):
        self.adapters = adapters
        self.enable_fallbacks = enable_fallbacks
        self.max_retries = max_retries
        self.base_delay = base_delay
        self.metrics = metrics
        self.breakers = CircuitRegistry(failure_threshold=breaker_threshold, recovery_time=breaker_recovery)

    def breaker_states(self) -> list[dict]:
        return self.breakers.snapshot()

    async def complete(
        self,
        messages: list[Message],
        tools: list[dict] | None = None,
        model: str | None = None,
    ) -> ModelResponse:
        chain = self.adapters if self.enable_fallbacks else self.adapters[:1]
        errors: list[str] = []
        for adapter in chain:
            if not adapter.available():
                errors.append(f"{adapter.name}: not configured")
                continue
            breaker = self.breakers.get(adapter.name)
            if not breaker.allow():
                errors.append(f"{adapter.name}: circuit open")
                if self.metrics:
                    self.metrics.counter("jarvis_provider_circuit_open_total", labels={"provider": adapter.name})
                continue
            adapter_model = model if adapter is chain[0] else None

            # Bind adapter/model explicitly so the closure captures this
            # iteration's values (not the loop variable's final value).
            async def _call(_adapter=adapter, _model=adapter_model):
                return await _adapter.complete(messages, tools=tools, model=_model)

            try:
                result = await retry_async(
                    _call,
                    max_attempts=self.max_retries + 1,
                    base_delay=self.base_delay,
                    retry_on=(TransientProviderError,),
                    on_retry=lambda attempt, exc, _name=adapter.name: errors.append(f"{_name} attempt {attempt}: {exc}"),
                )
                breaker.record_success()
                if self.metrics:
                    self.metrics.counter("jarvis_provider_calls_total", labels={"provider": adapter.name, "status": "ok"})
                log_event(logger, "provider.complete", provider=adapter.name)
                return result
            except TransientProviderError as exc:
                breaker.record_failure()
                errors.append(f"{adapter.name}: {exc}")
                if self.metrics:
                    self.metrics.counter("jarvis_provider_calls_total", labels={"provider": adapter.name, "status": "error"})
            except Exception as exc:
                breaker.record_failure()
                errors.append(f"{adapter.name} fatal: {exc}")
        raise AllProvidersFailed("; ".join(errors) or "no providers configured")


def build_router(settings: Settings, metrics=None) -> ProviderRouter:
    openai = OpenAIAdapter(settings.openai_api_key, default_model=settings.default_model_name)
    anthropic = AnthropicAdapter(settings.anthropic_api_key)
    mock = MockAdapter()
    if settings.default_model_provider.lower() == "anthropic":
        ordered: list[ModelAdapter] = [anthropic, openai, mock]
    elif settings.default_model_provider.lower() == "mock":
        ordered = [mock]
    else:
        ordered = [openai, anthropic, mock]
    return ProviderRouter(ordered, enable_fallbacks=settings.enable_fallbacks, metrics=metrics)
