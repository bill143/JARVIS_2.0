"""Provider failover router with retry+jitter, per-provider circuit breakers, metrics."""

from __future__ import annotations

from jarvis_adapters.anthropic_adapter import AnthropicAdapter
from jarvis_adapters.base import ModelAdapter, PermanentProviderError, TransientProviderError
from jarvis_adapters.mock_adapter import MockAdapter
from jarvis_adapters.nvidia_adapter import NvidiaAdapter
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
            except (TransientProviderError, PermanentProviderError) as exc:
                # Only provider errors may activate fallback. Anything else
                # (policy denials, programming errors) propagates unchanged.
                breaker.record_failure()
                kind = "transient" if isinstance(exc, TransientProviderError) else "permanent"
                errors.append(f"{adapter.name} ({kind}): {exc}")
                if self.metrics:
                    self.metrics.counter("jarvis_provider_calls_total", labels={"provider": adapter.name, "status": "error"})
        raise AllProvidersFailed("; ".join(errors) or "no providers configured")


def build_router(settings: Settings, metrics=None) -> ProviderRouter:
    provider = settings.default_model_provider.lower()
    # Each adapter keeps a sane default for its own platform; only the primary
    # provider inherits DEFAULT_MODEL_NAME (fallbacks must not be handed a
    # model id from a different platform).
    openai = OpenAIAdapter(
        settings.openai_api_key,
        default_model=settings.default_model_name if provider == "openai" else "gpt-4o",
    )
    anthropic = AnthropicAdapter(settings.anthropic_api_key)
    nvidia = NvidiaAdapter(
        settings.nvidia_api_key,
        default_model=settings.default_model_name if provider == "nvidia" else settings.nvidia_model,
        base_url=settings.nvidia_base_url,
    )
    mock = MockAdapter()
    if provider == "anthropic":
        ordered: list[ModelAdapter] = [anthropic, nvidia, openai, mock]
    elif provider == "nvidia":
        ordered = [nvidia, openai, anthropic, mock]
    elif provider == "mock":
        ordered = [mock]
    else:
        ordered = [openai, anthropic, nvidia, mock]
    if metrics:
        # Expose the failover order: position 1 = primary. Unconfigured adapters
        # still appear so dashboards can see the intended chain.
        for idx, adapter in enumerate(ordered, start=1):
            metrics.gauge(
                "jarvis_provider_chain_position",
                idx,
                labels={"provider": adapter.name, "configured": str(adapter.available()).lower()},
            )
    return ProviderRouter(ordered, enable_fallbacks=settings.enable_fallbacks, metrics=metrics)
