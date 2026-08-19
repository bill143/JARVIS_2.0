"""Anthropic Messages API adapter (Claude Sonnet fallback)."""

from __future__ import annotations

import httpx

from jarvis_adapters.base import ModelAdapter, PermanentProviderError, TransientProviderError
from jarvis_shared.schemas import Message, ModelResponse, ToolCall

API_URL = "https://api.anthropic.com/v1/messages"


def _to_anthropic(messages: list[Message]) -> tuple[str, list[dict]]:
    system = ""
    out: list[dict] = []
    for m in messages:
        if m.role == "system":
            system = (system + "\n" + m.content).strip()
        elif m.role == "tool":
            out.append({
                "role": "user",
                "content": [{"type": "tool_result", "tool_use_id": m.tool_call_id or "", "content": m.content}],
            })
        elif m.role == "assistant" and m.tool_calls:
            blocks: list[dict] = []
            if m.content:
                blocks.append({"type": "text", "text": m.content})
            blocks += [{"type": "tool_use", "id": tc.id, "name": tc.name, "input": tc.arguments} for tc in m.tool_calls]
            out.append({"role": "assistant", "content": blocks})
        else:
            out.append({"role": m.role, "content": m.content})
    return system, out


class AnthropicAdapter(ModelAdapter):
    name = "anthropic"

    def __init__(self, api_key: str, default_model: str = "claude-sonnet-5", timeout: float = 60.0):
        self.api_key = api_key
        self.default_model = default_model
        self.timeout = timeout

    def available(self) -> bool:
        return bool(self.api_key)

    async def complete(self, messages, tools=None, model=None) -> ModelResponse:
        model = model or self.default_model
        system, msgs = _to_anthropic(messages)
        body: dict = {"model": model, "max_tokens": 2048, "messages": msgs}
        if system:
            body["system"] = system
        if tools:
            body["tools"] = [
                {"name": t["name"], "description": t.get("description", ""), "input_schema": t.get("parameters", {"type": "object", "properties": {}})}
                for t in tools
            ]
        headers = {"x-api-key": self.api_key, "anthropic-version": "2023-06-01"}
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                resp = await client.post(API_URL, json=body, headers=headers)
        except httpx.HTTPError as exc:
            raise TransientProviderError(f"anthropic network error: {exc}") from exc
        if resp.status_code == 429 or resp.status_code >= 500:
            raise TransientProviderError(f"anthropic status {resp.status_code}")
        if resp.status_code != 200:
            # 4xx other than 429: auth/quota/bad-request — retrying is pointless.
            raise PermanentProviderError(f"anthropic error {resp.status_code}: {resp.text[:200]}")
        data = resp.json()
        text_parts: list[str] = []
        tool_calls: list[ToolCall] = []
        for block in data.get("content", []):
            if block.get("type") == "text":
                text_parts.append(block.get("text", ""))
            elif block.get("type") == "tool_use":
                tool_calls.append(ToolCall(id=block["id"], name=block["name"], arguments=block.get("input") or {}))
        return ModelResponse(content="\n".join(text_parts) or None, tool_calls=tool_calls, provider=self.name, model=model)
