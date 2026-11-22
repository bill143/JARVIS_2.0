"""Safe-workspace path enforcement. All file tools must go through resolve_safe()."""

from __future__ import annotations

from pathlib import Path

from jarvis_shared.errors import UnsafePath


def resolve_safe(workspace_root: Path, relative: str) -> Path:
    """Resolve a user-supplied path strictly inside the workspace root.

    Rejects absolute paths, drive-qualified paths, and any traversal that
    escapes the workspace after resolution.
    """
    if relative is None or str(relative).strip() == "":
        raise UnsafePath("Empty path")
    rel = Path(str(relative))
    if rel.is_absolute() or rel.drive:
        raise UnsafePath(f"Absolute paths are not allowed: {relative}")
    root = workspace_root.resolve()
    candidate = (root / rel).resolve()
    try:
        candidate.relative_to(root)
    except ValueError:
        raise UnsafePath(f"Path escapes the safe workspace: {relative}") from None
    return candidate
