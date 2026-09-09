"""Model provider adapters: OpenAI (primary), Anthropic (fallback), deterministic mock."""

from jarvis_adapters.base import ModelAdapter, TransientProviderError
from jarvis_adapters.router import ProviderRouter, build_router

__all__ = ["ModelAdapter", "TransientProviderError", "ProviderRouter", "build_router"]
