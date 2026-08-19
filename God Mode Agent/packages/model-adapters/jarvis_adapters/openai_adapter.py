"""OpenAI chat-completions adapter (GPT-4o primary)."""

from __future__ import annotations

import json

import httpx

from jarvis_adapters.base import ModelAdapter, PermanentProviderError, TransientProviderError
from jarvis_shared.schemas import Message, ModelResponse, ToolCall

API_URL = "https://api.openai.com/v1/chat/completions"


def _to_openai_messages(messages: list[Message]) -> list[dict]:
    out: list[dict] = []
    for m in messages:
        if m.role == "tool":
            out.append({"role": "tool", "tool_call_id": m.tool_call_id or "", "content": m.content})
        elif m.role == "assistant" and m.tool_calls:
            out.append({
                "role": "assistant",
                "content": m.content or None,
                "tool_calls": [
                    {
                        "id": tc.id,
                        "type": "function",
                        "function": {"name": tc.name, "arguments": json.dumps(tc.arguments)},
                    }
                    for tc in m.tool_calls
                ],
            })
        else:
            out.append({"role": m.role, "content": m.content})
    return out


class OpenAIAdapter(ModelAdapter):
    name = "openai"

    def __init__(self, api_key: str, default_model: str = "gpt-4o", timeout: float = 60.0, base_url: str = ""):
        self.api_key = api_key
        self.default_model = default_model
        self.timeout = timeout
        # Any OpenAI-compatible endpoint (NVIDIA NIM, OpenRouter, Groq, ...) can be
        # served by this adapter by overriding base_url.
        self.api_url = f"{base_url.rstrip('/')}/chat/completions" if base_url else API_URL

    def available(self) -> bool:
        return bool(self.api_key)

    async def complete(self, messages, tools=None, model=None) -> ModelResponse:
        model = model or self.default_model
        body: dict = {"model": model, "messages": _to_openai_messages(messages)}
        if tools:
            body["tools"] = [
                {"type": "function", "function": {"name": t["name"], "description": t.get("description", ""), "parameters": t.get("parameters", {"type": "object", "properties": {}})}}
                for t in tools
            ]
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                resp = await client.post(self.api_url, json=body, headers={"Authorization": f"Bearer {self.api_key}"})
        except httpx.HTTPError as exc:
            raise TransientProviderError(f"{self.name} network error: {exc}") from exc
        if resp.status_code == 429 or resp.status_code >= 500:
            raise TransientProviderError(f"{self.name} status {resp.status_code}")
        if resp.status_code != 200:
            # 4xx other than 429: auth/quota/bad-request — retrying is pointless.
            raise PermanentProviderError(f"{self.name} error {resp.status_code}: {resp.text[:200]}")
        choice = resp.json()["choices"][0]["message"]
        tool_calls = [
            ToolCall(id=tc["id"], name=tc["function"]["name"], arguments=json.loads(tc["function"].get("arguments") or "{}"))
            for tc in choice.get("tool_calls") or []
        ]
        return ModelResponse(content=choice.get("content"), tool_calls=tool_calls, provider=self.name, model=model)
