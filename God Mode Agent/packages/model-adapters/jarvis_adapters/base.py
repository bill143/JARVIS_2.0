"""Adapter contract every provider implements."""

from __future__ import annotations

from abc import ABC, abstractmethod

from jarvis_shared.schemas import Message, ModelResponse


class TransientProviderError(Exception):
    """Retryable provider failure (network, 429, 5xx)."""


class PermanentProviderError(Exception):
    """Non-retryable provider failure (auth, quota, bad request — 4xx except 429).

    The router skips straight to the next provider without retrying. Only these
    two provider error types may activate fallback; anything else (e.g. policy
    denials) propagates unchanged."""


class ModelAdapter(ABC):
    name: str = "base"

    @abstractmethod
    def available(self) -> bool:
        """Whether this adapter is configured (e.g., API key present)."""

    @abstractmethod
    async def complete(
        self,
        messages: list[Message],
        tools: list[dict] | None = None,
        model: str | None = None,
    ) -> ModelResponse:
        """Run one completion. `tools` is a neutral list of
        {"name", "description", "parameters"} JSON-schema dicts."""
