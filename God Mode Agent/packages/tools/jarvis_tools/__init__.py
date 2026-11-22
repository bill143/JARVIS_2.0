"""Tool framework and the default Phase-1 tool set."""

from __future__ import annotations

from jarvis_shared.config import Settings
from jarvis_tools.audit import AuditLog
from jarvis_tools.files import file_read, file_write
from jarvis_tools.python_exec import run_python_sandboxed
from jarvis_tools.registry import Tool, ToolRegistry, ToolResult
from jarvis_tools.web_search import web_search
from jarvis_tools.webcam import webcam_snapshot

DEFAULT_ALLOWLIST = {"python_exec", "web_search", "file_read", "file_write", "webcam_snapshot"}


def build_default_registry(settings: Settings, audit: AuditLog | None = None) -> ToolRegistry:
    registry = ToolRegistry(audit=audit, allowlist=set(DEFAULT_ALLOWLIST))
    workspace = settings.workspace_path
    timeout = settings.sandbox_timeout_seconds

    registry.register(Tool(
        name="python_exec",
        description="Execute Python code in a restricted sandbox. Use print() for output.",
        parameters={
            "type": "object",
            "properties": {"code": {"type": "string", "description": "Python source to run"}},
            "required": ["code"],
        },
        handler=lambda code: run_python_sandboxed(code, timeout=timeout),
    ))
    registry.register(Tool(
        name="web_search",
        description="Search the web. Returns a list of results with title, url, snippet.",
        parameters={
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "max_results": {"type": "integer", "description": "1-10, default 5"},
            },
            "required": ["query"],
        },
        handler=lambda query, max_results=5: web_search(query, max_results=min(max(int(max_results), 1), 10)),
    ))
    registry.register(Tool(
        name="file_read",
        description="Read a text file from the safe workspace (relative path only).",
        parameters={
            "type": "object",
            "properties": {"path": {"type": "string"}},
            "required": ["path"],
        },
        handler=lambda path: file_read(workspace, path),
    ))
    registry.register(Tool(
        name="file_write",
        description="Write a text file inside the safe workspace (relative path only).",
        parameters={
            "type": "object",
            "properties": {"path": {"type": "string"}, "content": {"type": "string"}},
            "required": ["path", "content"],
        },
        handler=lambda path, content: file_write(workspace, path, content),
    ))
    registry.register(Tool(
        name="webcam_snapshot",
        description="Capture a webcam frame (synthetic fallback if no camera) and analyze it.",
        parameters={"type": "object", "properties": {}},
        handler=lambda: webcam_snapshot(tesseract_cmd=settings.tesseract_cmd),
    ))
    return registry


__all__ = [
    "Tool", "ToolRegistry", "ToolResult", "AuditLog", "build_default_registry", "DEFAULT_ALLOWLIST",
]
