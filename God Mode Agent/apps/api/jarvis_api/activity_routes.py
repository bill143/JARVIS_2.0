"""ECHO Command Stage 3 routes: activity log reads + real system health checks.

All served by the loopback-bound API (Stage 1 standard). Statuses come from
actual responses — nothing here is fabricated or hardcoded.
"""

from __future__ import annotations

import asyncio
import shutil
import subprocess
from pathlib import Path

import httpx
from fastapi import APIRouter, Query, Request

from jarvis_api.deps import require_min_role
from jarvis_observability.activity import get_activity_log
from jarvis_shared.config import get_settings
from jarvis_shared.schemas import ok_envelope
from jarvis_voice.voices import load_voice_map

router = APIRouter()

_DEFAULT_TAILSCALE = r"C:\Program Files\Tailscale\tailscale.exe"


async def _check_kokoro() -> dict:
    settings = get_settings()
    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            resp = await client.get(f"{settings.kokoro_tts_url}/health")
        if resp.status_code != 200:
            return {"status": "down", "detail": f"HTTP {resp.status_code}"}
        body = resp.json()
        if body.get("ok"):
            return {"status": "ok", "detail": f"{len(body.get('voices', []))} voices, default {body.get('default', '?')}"}
        if body.get("loading"):
            return {"status": "degraded", "detail": "model loading"}
        return {"status": "down", "detail": body.get("error", "not ok")[:200]}
    except httpx.HTTPError as exc:
        return {"status": "down", "detail": f"{type(exc).__name__}: {exc}"[:200]}


def _tailscale_serve_status() -> dict:
    settings = get_settings()
    exe = settings.tailscale_exe or shutil.which("tailscale") or _DEFAULT_TAILSCALE
    if not Path(exe).exists():
        return {"status": "down", "detail": "tailscale.exe not found"}
    try:
        proc = subprocess.run([exe, "serve", "status"], capture_output=True, text=True, timeout=5)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"status": "down", "detail": f"{type(exc).__name__}: {exc}"[:200]}
    output = (proc.stdout or "") + (proc.stderr or "")
    if proc.returncode != 0:
        return {"status": "down", "detail": output.strip()[:200] or f"exit {proc.returncode}"}
    if "proxy" in output:
        first = next((line.strip() for line in output.splitlines() if line.strip()), "")
        return {"status": "ok", "detail": first[:200]}
    return {"status": "degraded", "detail": "serve running but no proxy configured"}


def register_activity_routes(app) -> None:
    @router.get("/activity/recent")
    async def activity_recent(request: Request, limit: int = Query(default=20, ge=1, le=200)):
        require_min_role(request, "user")
        return ok_envelope({"rows": get_activity_log().recent(limit)})

    @router.get("/activity/agents")
    async def activity_agents(request: Request):
        require_min_role(request, "user")
        configured = sorted(load_voice_map(get_settings())["agents"])
        return ok_envelope({"agents": get_activity_log().agent_summary(configured)})

    @router.get("/system/health")
    async def system_health(request: Request):
        require_min_role(request, "user")
        kokoro, tailscale = await asyncio.gather(
            _check_kokoro(), asyncio.to_thread(_tailscale_serve_status)
        )
        return ok_envelope({"services": {
            # if this handler is answering, the backend is up by definition
            "backend": {"status": "ok", "detail": "api responding"},
            "kokoro": kokoro,
            "tailscale": tailscale,
        }})

    app.include_router(router)
