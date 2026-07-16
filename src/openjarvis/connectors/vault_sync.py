"""Git-based synchronization for an Obsidian / Markdown vault.

Watches a server-side-configurable vault directory (``config.vault``) and, when
it is a git repository, auto-commits local edits and pulls remote changes on an
interval, then re-indexes the vault into the knowledge store. Because the path
and cadence come from config (config.toml ``[vault]``, ``jarvis config set``, or
the runtime API), they can change without a rebuild.

The module is layered so it stays testable:

* thin ``git_*`` subprocess helpers (no external git library),
* :class:`VaultSyncManager` — one synchronous ``sync_once()`` tick
  (commit -> pull -> reindex-on-change), driven by a change fingerprint, and
* :class:`VaultWatcher` — a daemon thread that calls ``sync_once()`` on an
  interval, mirroring the existing scheduler thread pattern.
"""

from __future__ import annotations

import logging
import shutil
import subprocess
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, List, Optional, Tuple

logger = logging.getLogger(__name__)

_GIT_TIMEOUT = 60
# Directories never counted toward the vault change fingerprint / never indexed.
_SKIP_DIRS = frozenset(
    {".git", ".obsidian", ".trash", "__pycache__", "node_modules", ".venv"}
)


@dataclass
class GitResult:
    """Outcome of a single git subprocess invocation."""

    ok: bool
    output: str
    returncode: int = 0


def git_available() -> bool:
    """True if a git binary is on PATH."""
    return shutil.which("git") is not None


def run_git(args: List[str], *, cwd: str, timeout: int = _GIT_TIMEOUT) -> GitResult:
    """Run ``git <args>`` in ``cwd`` and capture the result."""
    if not git_available():
        return GitResult(False, "git binary not found on PATH", 127)
    try:
        proc = subprocess.run(
            ["git", *args],
            capture_output=True,
            text=True,
            cwd=str(cwd),
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        return GitResult(False, f"git {' '.join(args)} timed out", 124)
    except FileNotFoundError:
        return GitResult(False, "git binary not found", 127)
    out = (proc.stdout or "").strip()
    if proc.stderr:
        out = (out + "\n" + proc.stderr.strip()).strip()
    return GitResult(proc.returncode == 0, out, proc.returncode)


def is_git_repo(path: str) -> bool:
    """True if ``path`` is inside a git work tree."""
    p = Path(path).expanduser()
    if not p.is_dir():
        return False
    res = run_git(["rev-parse", "--is-inside-work-tree"], cwd=str(p))
    return res.ok and res.output.strip().splitlines()[:1] == ["true"]


def has_changes(path: str) -> bool:
    """True if the working tree has uncommitted changes."""
    return bool(run_git(["status", "--porcelain"], cwd=path).output.strip())


def current_head(path: str) -> str:
    """Return the current commit SHA, or '' if unavailable."""
    res = run_git(["rev-parse", "HEAD"], cwd=path)
    return res.output.strip() if res.ok else ""


def commit_all(path: str, message: str) -> GitResult:
    """Stage everything and commit with ``message``."""
    added = run_git(["add", "-A"], cwd=path)
    if not added.ok:
        return added
    return run_git(["commit", "-m", message], cwd=path)


def pull(path: str, remote: str = "origin", branch: str = "") -> GitResult:
    """Rebase-pull from ``remote`` (autostashing local work)."""
    args = ["pull", "--rebase", "--autostash", remote]
    if branch:
        args.append(branch)
    return run_git(args, cwd=path)


def push(path: str, remote: str = "origin", branch: str = "") -> GitResult:
    """Push the current branch to ``remote``."""
    args = ["push", remote]
    if branch:
        args.append(branch)
    return run_git(args, cwd=path)


def _fingerprint(path: str) -> Tuple[int, float]:
    """Cheap change signature for a vault: (file count, latest mtime).

    Files inside skipped directories (``.git``, ``.obsidian`` ...) are ignored so
    git bookkeeping and Obsidian internals don't trip constant re-indexing.
    """
    root = Path(path).expanduser()
    count = 0
    latest = 0.0
    for f in root.rglob("*"):
        if not f.is_file():
            continue
        if any(part in _SKIP_DIRS for part in f.relative_to(root).parts):
            continue
        count += 1
        try:
            latest = max(latest, f.stat().st_mtime)
        except OSError:
            pass
    return count, latest


@dataclass
class VaultSyncResult:
    """What a single :meth:`VaultSyncManager.sync_once` tick did."""

    ok: bool = True
    committed: bool = False
    pulled: bool = False
    pushed: bool = False
    reindexed: bool = False
    changed: bool = False
    messages: List[str] = field(default_factory=list)
    errors: List[str] = field(default_factory=list)


class VaultSyncManager:
    """One-shot vault sync: commit local edits, pull remote, reindex on change.

    ``reindex`` is a callback ``(vault_path) -> None`` so the manager stays
    decoupled from the connector/pipeline stack (and trivially testable). The
    default production wiring is :func:`default_reindex`.
    """

    def __init__(
        self,
        vault_config,  # core.config.VaultConfig  # noqa: ANN001
        *,
        reindex: Optional[Callable[[str], None]] = None,
    ) -> None:
        self._cfg = vault_config
        self._reindex = reindex
        self._last_fp: Optional[Tuple[int, float]] = None

    @property
    def path(self) -> str:
        return getattr(self._cfg, "path", "") or ""

    def sync_once(self, *, force_reindex: bool = False) -> VaultSyncResult:
        res = VaultSyncResult()
        path = Path(self.path).expanduser()
        if not self.path or not path.is_dir():
            res.ok = False
            res.errors.append(f"Vault path is not a directory: {self.path!r}")
            return res

        spath = str(path)
        if getattr(self._cfg, "git_sync", True) and is_git_repo(spath):
            if getattr(self._cfg, "auto_commit", True) and has_changes(spath):
                commit = commit_all(
                    spath,
                    getattr(self._cfg, "commit_message", "chore(vault): auto-sync"),
                )
                res.committed = commit.ok
                (res.messages if commit.ok else res.errors).append(
                    f"commit: {commit.output[:200]}"
                )
            if getattr(self._cfg, "auto_pull", True):
                pulled = pull(
                    spath,
                    getattr(self._cfg, "remote", "origin"),
                    getattr(self._cfg, "branch", ""),
                )
                res.pulled = pulled.ok
                (res.messages if pulled.ok else res.errors).append(
                    f"pull: {pulled.output[:200]}"
                )

        # Change detection via fingerprint captures both local edits and any
        # files that arrived via the pull above.
        fp = _fingerprint(spath)
        res.changed = force_reindex or self._last_fp is None or fp != self._last_fp
        self._last_fp = fp

        if (
            res.changed
            and getattr(self._cfg, "reindex_on_change", True)
            and self._reindex is not None
        ):
            try:
                self._reindex(spath)
                res.reindexed = True
                res.messages.append("reindex: ok")
            except Exception as exc:  # pragma: no cover - defensive
                res.ok = False
                res.errors.append(f"reindex: {exc}")

        if res.errors:
            res.ok = False
        return res


class VaultWatcher:
    """Daemon thread that runs :meth:`VaultSyncManager.sync_once` on an interval."""

    def __init__(self, manager: VaultSyncManager, *, poll_interval: int = 30) -> None:
        self._mgr = manager
        self._interval = max(2, int(poll_interval))
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self) -> None:
        if self.running:
            return
        self._stop.clear()
        self._thread = threading.Thread(
            target=self._loop, name="jarvis-vault-watcher", daemon=True
        )
        self._thread.start()
        logger.info(
            "Vault watcher started (path=%s, interval=%ss)",
            self._mgr.path,
            self._interval,
        )

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                self._mgr.sync_once()
            except Exception as exc:  # pragma: no cover - defensive
                logger.warning("Vault sync tick failed: %s", exc)
            self._stop.wait(self._interval)

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=5)


def default_reindex(vault_path: str) -> None:
    """Re-index the vault into the knowledge store (production wiring)."""
    from openjarvis.connectors.obsidian import ObsidianConnector
    from openjarvis.connectors.pipeline import IngestionPipeline
    from openjarvis.connectors.store import KnowledgeStore
    from openjarvis.connectors.sync_engine import SyncEngine

    store = KnowledgeStore()
    engine = SyncEngine(pipeline=IngestionPipeline(store=store))
    engine.sync(ObsidianConnector(vault_path=vault_path))


def build_vault_watcher(config) -> Optional[VaultWatcher]:
    """Build a watcher from ``config.vault`` if vault sync is enabled, else None."""
    vcfg = getattr(config, "vault", None)
    if not vcfg or not getattr(vcfg, "enabled", False):
        return None
    if not getattr(vcfg, "path", ""):
        logger.warning(
            "Vault sync enabled but no path configured; watcher not started."
        )
        return None
    manager = VaultSyncManager(vcfg, reindex=default_reindex)
    return VaultWatcher(manager, poll_interval=getattr(vcfg, "poll_interval", 30))


__all__ = [
    "GitResult",
    "git_available",
    "run_git",
    "is_git_repo",
    "has_changes",
    "current_head",
    "commit_all",
    "pull",
    "push",
    "VaultSyncResult",
    "VaultSyncManager",
    "VaultWatcher",
    "default_reindex",
    "build_vault_watcher",
]
