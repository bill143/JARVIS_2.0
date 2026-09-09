"""Tool framework: registration, allowlist, JSON-schema validation, audited execution."""

from __future__ import annotations

import inspect
import json
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any

from jarvis_shared.errors import ToolNotAllowed, ToolValidationError
from jarvis_shared.logging import get_logger, log_event
from jarvis_tools.audit import AuditLog

logger = get_logger("jarvis.tools")

_JSON_TYPES: dict[str, tuple[type, ...]] = {
    "string": (str,),
    "number": (int, float),
    "integer": (int,),
    "boolean": (bool,),
    "object": (dict,),
    "array": (list,),
}


@dataclass
class Tool:
    name: str
    description: str
    parameters: dict = field(default_factory=lambda: {"type": "object", "properties": {}})
    handler: Callable[..., Awaitable[Any]] | Callable[..., Any] = None  # type: ignore[assignment]


@dataclass
class ToolResult:
    tool: str
    status: str  # "ok" | "error"
    output: Any
    duration_ms: float


class ToolRegistry:
    def __init__(self, audit: AuditLog | None = None, allowlist: set[str] | None = None):
        self._tools: dict[str, Tool] = {}
        self.audit = audit
        self.allowlist = allowlist  # None means "everything registered is allowed"

    def register(self, tool: Tool) -> None:
        self._tools[tool.name] = tool

    def names(self) -> list[str]:
        return sorted(self._tools)

    def schemas(self) -> list[dict]:
        return [
            {"name": t.name, "description": t.description, "parameters": t.parameters}
            for t in self._tools.values()
        ]

    def validate_args(self, name: str, args: dict) -> None:
        tool = self._tools[name]
        schema = tool.parameters or {}
        props: dict = schema.get("properties", {})
        required: list[str] = schema.get("required", [])
        if not isinstance(args, dict):
            raise ToolValidationError(f"{name}: arguments must be an object")
        for key in required:
            if key not in args:
                raise ToolValidationError(f"{name}: missing required argument '{key}'")
        for key, value in args.items():
            if key not in props:
                raise ToolValidationError(f"{name}: unknown argument '{key}'")
            expected = props[key].get("type")
            if expected in _JSON_TYPES:
                py_types = _JSON_TYPES[expected]
                if isinstance(value, bool) and expected in ("number", "integer"):
                    raise ToolValidationError(f"{name}: argument '{key}' expected {expected}, got boolean")
                if not isinstance(value, py_types):
                    raise ToolValidationError(
                        f"{name}: argument '{key}' expected {expected}, got {type(value).__name__}"
                    )

    async def execute(self, name: str, arguments: dict | None = None, session_id: str = "default") -> ToolResult:
        arguments = arguments or {}
        args_summary = json.dumps(arguments, default=str)[:400]
        start = time.perf_counter()

        def _finish(status: str, output: Any) -> ToolResult:
            duration_ms = round((time.perf_counter() - start) * 1000, 2)
            if self.audit:
                try:
                    self.audit.record(session_id, name, args_summary, status, duration_ms)
                except Exception:
                    logger.warning("audit log write failed")
            log_event(logger, "tool.execute", tool=name, status=status, duration_ms=duration_ms, session_id=session_id)
            return ToolResult(tool=name, status=status, output=output, duration_ms=duration_ms)

        if name not in self._tools:
            _finish("error", {"error": f"unknown tool '{name}'"})
            raise ToolNotAllowed(f"Tool '{name}' is not registered")
        if self.allowlist is not None and name not in self.allowlist:
            _finish("error", {"error": "tool not in allowlist"})
            raise ToolNotAllowed(f"Tool '{name}' is not in the allowlist")
        try:
            self.validate_args(name, arguments)
        except ToolValidationError:
            _finish("error", {"error": "validation failed"})
            raise
        try:
            handler = self._tools[name].handler
            value = handler(**arguments)
            if inspect.isawaitable(value):
                value = await value
            return _finish("ok", value)
        except Exception as exc:
            return _finish("error", {"error": f"{type(exc).__name__}: {exc}"})
