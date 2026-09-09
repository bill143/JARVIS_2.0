"""Deterministic offline adapter. Used when no API keys are configured and in tests.

Behavior contract (relied on by integration/e2e tests):
- "compute <expr>" / "calculate <expr>"  -> python_exec tool call printing the expression
- "search <query>"                       -> web_search tool call
- "snapshot" / "webcam"                  -> webcam_snapshot tool call
- after a tool result message            -> final answer embedding the tool output
- anything else                          -> deterministic echo reply
"""

from __future__ import annotations

import re

from jarvis_adapters.base import ModelAdapter
from jarvis_shared.schemas import Message, ModelResponse, ToolCall

_COMPUTE_RE = re.compile(r"\b(?:compute|calculate|eval)\s+(.+)", re.IGNORECASE)
_SEARCH_RE = re.compile(r"\bsearch(?:\s+for)?\s+(.+)", re.IGNORECASE)


class MockAdapter(ModelAdapter):
    name = "mock"

    def __init__(self, default_model: str = "mock-1"):
        self.default_model = default_model

    def available(self) -> bool:
        return True

    async def complete(self, messages: list[Message], tools=None, model=None) -> ModelResponse:
        model = model or self.default_model
        last = messages[-1] if messages else Message(role="user", content="")
        tool_names = {t["name"] for t in tools} if tools else set()

        if last.role == "tool":
            output = (last.content or "").strip()
            tool_name = last.name or "tool"
            reply = f"I ran the `{tool_name}` tool. Result: {output[:800]}"
            return ModelResponse(content=reply, provider=self.name, model=model)

        text = last.content or ""
        m = _COMPUTE_RE.search(text)
        if m and "python_exec" in tool_names:
            expr = m.group(1).strip().rstrip("?.!")
            code = f"print({expr})"
            return ModelResponse(
                tool_calls=[ToolCall(id="call_mock_1", name="python_exec", arguments={"code": code})],
                provider=self.name, model=model,
            )
        m = _SEARCH_RE.search(text)
        if m and "web_search" in tool_names:
            return ModelResponse(
                tool_calls=[ToolCall(id="call_mock_2", name="web_search", arguments={"query": m.group(1).strip()})],
                provider=self.name, model=model,
            )
        if ("snapshot" in text.lower() or "webcam" in text.lower()) and "webcam_snapshot" in tool_names:
            return ModelResponse(
                tool_calls=[ToolCall(id="call_mock_3", name="webcam_snapshot", arguments={})],
                provider=self.name, model=model,
            )
        reply = f"[offline-mock] You said: {text.strip()[:500]}"
        return ModelResponse(content=reply, provider=self.name, model=model)
