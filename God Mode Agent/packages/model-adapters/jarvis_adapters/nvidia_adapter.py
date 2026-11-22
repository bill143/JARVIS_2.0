"""NVIDIA NIM adapter (integrate.api.nvidia.com — OpenAI-compatible chat completions)."""

from __future__ import annotations

from jarvis_adapters.openai_adapter import OpenAIAdapter

DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1"


class NvidiaAdapter(OpenAIAdapter):
    name = "nvidia"

    def __init__(
        self,
        api_key: str,
        default_model: str = "nvidia/nemotron-3-super-120b-a12b",
        timeout: float = 120.0,
        base_url: str = DEFAULT_BASE_URL,
    ):
        super().__init__(api_key, default_model=default_model, timeout=timeout, base_url=base_url or DEFAULT_BASE_URL)
