"""Tests for git-based Obsidian vault synchronization."""

from __future__ import annotations

import shutil
import subprocess
import time
from pathlib import Path

import pytest

from openjarvis.connectors.vault_sync import (
    VaultSyncManager,
    VaultWatcher,
    commit_all,
    current_head,
    has_changes,
    is_git_repo,
    pull,
)
from openjarvis.core.config import VaultConfig

pytestmark = pytest.mark.skipif(
    shutil.which("git") is None, reason="git binary not available"
)


def _git(cwd: Path, *args: str) -> None:
    subprocess.run(
        ["git", *args],
        cwd=str(cwd),
        check=True,
        capture_output=True,
        text=True,
    )


def _init_repo(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)
    _git(path, "init", "-b", "main")
    _git(path, "config", "user.email", "test@example.com")
    _git(path, "config", "user.name", "Test")
    _git(path, "config", "commit.gpgsign", "false")


def _write(path: Path, name: str, text: str) -> None:
    (path / name).write_text(text)


# ---------------------------------------------------------------------------
# git helpers
# ---------------------------------------------------------------------------


def test_is_git_repo_and_changes(tmp_path):
    repo = tmp_path / "vault"
    _init_repo(repo)
    assert is_git_repo(str(repo))
    assert not is_git_repo(str(tmp_path / "not_a_repo"))

    _write(repo, "note.md", "# Hello")
    assert has_changes(str(repo))
    res = commit_all(str(repo), "add note")
    assert res.ok, res.output
    assert not has_changes(str(repo))
    assert current_head(str(repo))  # non-empty SHA


# ---------------------------------------------------------------------------
# VaultSyncManager
# ---------------------------------------------------------------------------


def _cfg(path: Path, **kw) -> VaultConfig:
    base = dict(
        enabled=True,
        path=str(path),
        git_sync=True,
        auto_commit=True,
        auto_pull=False,
        commit_message="chore(vault): auto-sync",
    )
    base.update(kw)
    return VaultConfig(**base)


def test_sync_once_commits_local_edits_and_reindexes(tmp_path):
    repo = tmp_path / "vault"
    _init_repo(repo)
    _write(repo, "seed.md", "seed")
    commit_all(str(repo), "seed")

    reindexed = []
    mgr = VaultSyncManager(_cfg(repo), reindex=lambda p: reindexed.append(p))

    # New local edit -> committed and reindexed (first tick always indexes).
    _write(repo, "new.md", "# New note")
    res = mgr.sync_once()
    assert res.ok, res.errors
    assert res.committed
    assert res.reindexed
    assert not has_changes(str(repo))  # working tree is clean after commit
    assert reindexed == [str(repo)]

    # No change -> no reindex on the next tick.
    res2 = mgr.sync_once()
    assert not res2.committed
    assert not res2.changed
    assert not res2.reindexed
    assert len(reindexed) == 1


def test_sync_once_pull_brings_remote_changes_and_reindexes(tmp_path):
    bare = tmp_path / "remote.git"
    bare.mkdir()
    _git(bare, "init", "--bare", "-b", "main")

    work1 = tmp_path / "work1"
    _git(tmp_path, "clone", str(bare), str(work1))
    _git(work1, "config", "user.email", "a@example.com")
    _git(work1, "config", "user.name", "A")
    _write(work1, "one.md", "one")
    _git(work1, "add", "-A")
    _git(work1, "commit", "-m", "one")
    _git(work1, "push", "-u", "origin", "main")

    work2 = tmp_path / "work2"
    _git(tmp_path, "clone", str(bare), str(work2))

    reindexed = []
    cfg = _cfg(work2, auto_commit=True, auto_pull=True, remote="origin", branch="main")
    mgr = VaultSyncManager(cfg, reindex=lambda p: reindexed.append(p))

    # Prime the fingerprint (work2 currently only has one.md).
    mgr.sync_once()
    reindexed.clear()

    # Only now does a new commit land on the remote from work1.
    _write(work1, "two.md", "two")
    _git(work1, "add", "-A")
    _git(work1, "commit", "-m", "two")
    _git(work1, "push", "origin", "main")

    res = mgr.sync_once()
    assert res.pulled, res.errors
    assert (work2 / "two.md").exists()  # remote change pulled in
    assert res.reindexed
    assert reindexed == [str(work2)]


def test_direct_pull_helper(tmp_path):
    bare = tmp_path / "r.git"
    bare.mkdir()
    _git(bare, "init", "--bare", "-b", "main")
    w1 = tmp_path / "w1"
    _git(tmp_path, "clone", str(bare), str(w1))
    _git(w1, "config", "user.email", "a@example.com")
    _git(w1, "config", "user.name", "A")
    _write(w1, "x.md", "x")
    _git(w1, "add", "-A")
    _git(w1, "commit", "-m", "x")
    _git(w1, "push", "-u", "origin", "main")

    w2 = tmp_path / "w2"
    _git(tmp_path, "clone", str(bare), str(w2))
    _write(w1, "y.md", "y")
    _git(w1, "add", "-A")
    _git(w1, "commit", "-m", "y")
    _git(w1, "push", "origin", "main")

    res = pull(str(w2), "origin", "main")
    assert res.ok, res.output
    assert (w2 / "y.md").exists()


def test_sync_once_missing_path_is_error(tmp_path):
    cfg = _cfg(tmp_path / "does_not_exist")
    mgr = VaultSyncManager(cfg, reindex=lambda p: None)
    res = mgr.sync_once()
    assert not res.ok
    assert res.errors


def test_sync_once_non_git_dir_still_reindexes(tmp_path):
    # A plain (non-git) vault directory: no commit/pull, but change-detect
    # + reindex still works.
    vault = tmp_path / "plain"
    vault.mkdir()
    _write(vault, "a.md", "a")
    reindexed = []
    mgr = VaultSyncManager(_cfg(vault), reindex=lambda p: reindexed.append(p))
    res = mgr.sync_once()
    assert res.ok
    assert not res.committed
    assert res.reindexed
    assert reindexed == [str(vault)]


# ---------------------------------------------------------------------------
# VaultWatcher thread lifecycle
# ---------------------------------------------------------------------------


class _FakeManager:
    def __init__(self):
        self.ticks = 0
        self.path = "/fake"

    def sync_once(self, **_):
        self.ticks += 1


def test_watcher_start_stop_ticks():
    fake = _FakeManager()
    watcher = VaultWatcher(fake, poll_interval=2)  # min clamps to 2s
    assert not watcher.running
    watcher.start()
    assert watcher.running
    # First tick runs immediately.
    time.sleep(0.3)
    watcher.stop()
    assert not watcher.running
    assert fake.ticks >= 1
