"""Tests for the dynamic vault-sync API routes (Stage 3)."""

from __future__ import annotations

import shutil
import subprocess
from pathlib import Path
from types import SimpleNamespace

import pytest

pytest.importorskip("fastapi", reason="openjarvis[server] not installed")

from fastapi import FastAPI  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from openjarvis.core.config import VaultConfig  # noqa: E402
from openjarvis.server.vault_router import create_vault_router  # noqa: E402

pytestmark = pytest.mark.skipif(
    shutil.which("git") is None, reason="git binary not available"
)


def _git(cwd: Path, *args: str) -> None:
    subprocess.run(
        ["git", *args], cwd=str(cwd), check=True, capture_output=True, text=True
    )


def _init_repo(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)
    _git(path, "init", "-b", "main")
    _git(path, "config", "user.email", "test@example.com")
    _git(path, "config", "user.name", "Test")
    _git(path, "config", "commit.gpgsign", "false")


def _client(vcfg: VaultConfig) -> TestClient:
    app = FastAPI()
    app.state.config = SimpleNamespace(vault=vcfg)
    app.state.vault_watcher = None
    app.include_router(create_vault_router())
    return TestClient(app)


def test_vault_status(tmp_path):
    repo = tmp_path / "vault"
    _init_repo(repo)
    client = _client(VaultConfig(enabled=False, path=str(repo)))
    resp = client.get("/v1/vault")
    assert resp.status_code == 200
    data = resp.json()
    assert data["path"] == str(repo)
    assert data["is_git_repo"] is True
    assert data["git_available"] is True
    assert data["watcher_running"] is False


def test_vault_update_config_mutates_live(tmp_path):
    repo = tmp_path / "vault"
    _init_repo(repo)
    vcfg = VaultConfig(enabled=False, path="", poll_interval=30)
    client = _client(vcfg)
    resp = client.post(
        "/v1/vault/config",
        json={"path": str(repo), "poll_interval": 15, "auto_pull": False},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["path"] == str(repo)
    assert data["poll_interval"] == 15
    assert data["auto_pull"] is False
    # No watcher started while enabled stays False.
    assert data["watcher_running"] is False
    # Live config object was mutated (no rebuild).
    assert vcfg.path == str(repo)
    assert vcfg.poll_interval == 15


def test_vault_manual_sync_commits(tmp_path):
    repo = tmp_path / "vault"
    _init_repo(repo)
    (repo / "seed.md").write_text("seed")
    _git(repo, "add", "-A")
    _git(repo, "commit", "-m", "seed")

    # reindex disabled so the manual sync exercises git only (no heavy indexing).
    vcfg = VaultConfig(
        enabled=True,
        path=str(repo),
        auto_pull=False,
        reindex_on_change=False,
    )
    client = _client(vcfg)

    # Uncommitted local edit -> /sync should commit it.
    (repo / "note.md").write_text("# note")
    resp = client.post("/v1/vault/sync")
    assert resp.status_code == 200
    data = resp.json()
    assert data["ok"] is True
    assert data["committed"] is True
    assert data["reindexed"] is False  # disabled


def test_vault_sync_requires_path(tmp_path):
    client = _client(VaultConfig(enabled=True, path=""))
    resp = client.post("/v1/vault/sync")
    assert resp.status_code == 400
