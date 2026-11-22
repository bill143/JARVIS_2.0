"""FastAPI routes for dynamic Obsidian vault synchronization.

Lets an operator inspect, reconfigure (path/interval/toggles), and manually
trigger vault git-sync at runtime — no rebuild or restart required. The
persistent source of truth is config.toml ``[vault]``; these routes mutate the
live ``app.state.config.vault`` for the running process.
"""

from __future__ import annotations

import logging
from typing import Optional

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel

from openjarvis.connectors.vault_sync import (
    VaultSyncManager,
    VaultWatcher,
    default_reindex,
    git_available,
    is_git_repo,
)

logger = logging.getLogger(__name__)


class VaultConfigRequest(BaseModel):
    """Runtime vault configuration update (all fields optional)."""

    enabled: Optional[bool] = None
    path: Optional[str] = None
    poll_interval: Optional[int] = None
    git_sync: Optional[bool] = None
    auto_commit: Optional[bool] = None
    auto_pull: Optional[bool] = None
    remote: Optional[str] = None
    branch: Optional[str] = None
    reindex_on_change: Optional[bool] = None


def _vault_cfg(request: Request):
    cfg = getattr(request.app.state, "config", None)
    vcfg = getattr(cfg, "vault", None)
    if vcfg is None:
        raise HTTPException(status_code=503, detail="Vault config unavailable")
    return vcfg


def _status_payload(vcfg) -> dict:
    path = getattr(vcfg, "path", "") or ""
    return {
        "enabled": bool(getattr(vcfg, "enabled", False)),
        "path": path,
        "poll_interval": getattr(vcfg, "poll_interval", 30),
        "git_sync": bool(getattr(vcfg, "git_sync", True)),
        "auto_commit": bool(getattr(vcfg, "auto_commit", True)),
        "auto_pull": bool(getattr(vcfg, "auto_pull", True)),
        "remote": getattr(vcfg, "remote", "origin"),
        "branch": getattr(vcfg, "branch", ""),
        "git_available": git_available(),
        "is_git_repo": bool(path) and is_git_repo(path),
    }


def create_vault_router() -> APIRouter:
    router = APIRouter(prefix="/v1/vault", tags=["vault"])

    @router.get("")
    def vault_status(request: Request):
        vcfg = _vault_cfg(request)
        payload = _status_payload(vcfg)
        watcher = getattr(request.app.state, "vault_watcher", None)
        payload["watcher_running"] = bool(watcher and watcher.running)
        return payload

    @router.post("/config")
    def update_vault_config(req: VaultConfigRequest, request: Request):
        """Update vault config live and (re)start/stop the watcher accordingly."""
        vcfg = _vault_cfg(request)
        for field_name, value in req.model_dump(exclude_none=True).items():
            if hasattr(vcfg, field_name):
                setattr(vcfg, field_name, value)

        # Restart the watcher to pick up the new path/interval/enabled state.
        old = getattr(request.app.state, "vault_watcher", None)
        if old is not None:
            old.stop()
        request.app.state.vault_watcher = None
        if getattr(vcfg, "enabled", False) and getattr(vcfg, "path", ""):
            manager = VaultSyncManager(vcfg, reindex=default_reindex)
            watcher = VaultWatcher(
                manager, poll_interval=getattr(vcfg, "poll_interval", 30)
            )
            watcher.start()
            request.app.state.vault_watcher = watcher

        payload = _status_payload(vcfg)
        payload["watcher_running"] = bool(
            getattr(request.app.state, "vault_watcher", None)
        )
        return payload

    @router.post("/sync")
    def trigger_vault_sync(request: Request):
        """Run one commit -> pull -> reindex sync cycle immediately."""
        vcfg = _vault_cfg(request)
        if not getattr(vcfg, "path", ""):
            raise HTTPException(status_code=400, detail="No vault path configured")
        manager = VaultSyncManager(vcfg, reindex=default_reindex)
        result = manager.sync_once(force_reindex=True)
        return {
            "ok": result.ok,
            "committed": result.committed,
            "pulled": result.pulled,
            "reindexed": result.reindexed,
            "changed": result.changed,
            "messages": result.messages,
            "errors": result.errors,
        }

    return router


__all__ = ["create_vault_router"]
