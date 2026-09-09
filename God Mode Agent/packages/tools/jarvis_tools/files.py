"""Scoped file read/write tools — safe workspace only."""

from __future__ import annotations

from pathlib import Path

from jarvis_shared.paths import resolve_safe

MAX_READ_BYTES = 64 * 1024
MAX_WRITE_BYTES = 256 * 1024


def file_read(workspace_root: Path, path: str) -> dict:
    target = resolve_safe(workspace_root, path)
    if not target.exists() or not target.is_file():
        return {"path": path, "exists": False, "content": "", "note": "file not found in workspace"}
    data = target.read_bytes()[:MAX_READ_BYTES]
    return {
        "path": path,
        "exists": True,
        "size": target.stat().st_size,
        "content": data.decode("utf-8", errors="replace"),
    }


def file_write(workspace_root: Path, path: str, content: str) -> dict:
    if len(content.encode("utf-8")) > MAX_WRITE_BYTES:
        return {"path": path, "written": False, "note": f"content exceeds {MAX_WRITE_BYTES} bytes"}
    target = resolve_safe(workspace_root, path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")
    return {"path": path, "written": True, "bytes": len(content.encode("utf-8"))}
