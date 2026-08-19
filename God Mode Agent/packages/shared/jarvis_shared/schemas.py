"""Strict Pydantic schemas shared across the agent, API, and tools."""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field


class ToolCall(BaseModel):
    id: str
    name: str
    arguments: dict[str, Any] = Field(default_factory=dict)


class Message(BaseModel):
    role: Literal["system", "user", "assistant", "tool"]
    content: str = ""
    name: str | None = None
    tool_call_id: str | None = None
    tool_calls: list[ToolCall] = Field(default_factory=list)


class ModelResponse(BaseModel):
    content: str | None = None
    tool_calls: list[ToolCall] = Field(default_factory=list)
    provider: str = "unknown"
    model: str = "unknown"


class ToolEvent(BaseModel):
    tool: str
    status: Literal["ok", "error"]
    summary: str = ""
    duration_ms: float = 0.0


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=32000)
    session_id: str = Field(default="default", max_length=128)
    user_id: str = Field(default="default", max_length=128)


class ChatData(BaseModel):
    reply: str
    provider: str
    model: str
    iterations: int
    tool_events: list[ToolEvent] = Field(default_factory=list)
    session_id: str


class ToolExecuteRequest(BaseModel):
    tool: str = Field(min_length=1, max_length=64)
    arguments: dict[str, Any] = Field(default_factory=dict)
    session_id: str = Field(default="default", max_length=128)


class MemoryUpsertRequest(BaseModel):
    text: str = Field(min_length=1, max_length=32000)
    user_id: str = Field(default="default", max_length=128)
    # Empty session_id = user-level memory (matches /memory/search's default namespace).
    session_id: str = Field(default="", max_length=128)
    record_id: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)


class MemoryHit(BaseModel):
    id: str
    text: str
    score: float
    metadata: dict[str, Any] = Field(default_factory=dict)


class VisionAnalysis(BaseModel):
    ok: bool = True
    source: str = "upload"
    width: int | None = None
    height: int | None = None
    mean_brightness: float | None = None
    ocr_text: str = ""
    ocr_engine: str = "unavailable"
    caption: str = ""
    note: str = ""


class ErrorInfo(BaseModel):
    code: str
    message: str
    request_id: str


def ok_envelope(data: Any) -> dict:
    return {"success": True, "data": data}


def error_envelope(code: str, message: str, request_id: str) -> dict:
    return {"success": False, "error": {"code": code, "message": message, "requestId": request_id}}
