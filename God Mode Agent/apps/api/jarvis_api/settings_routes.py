"""Run 2 settings routes: model tiers (persisted, hot-swap) and per-agent
voices (DB-persisted overrides over voices.yaml) with live Kokoro preview.

All state changes land in the runtime store (jarvis.db) so they survive a
restart and take effect on the next request without one.
"""

from __future__ import annotations

import time

import httpx
from fastapi import APIRouter, Query, Request, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from jarvis_api.deps import require_min_role
from jarvis_observability.activity import get_activity_log
from jarvis_shared.config import get_settings
from jarvis_shared.runtime_store import get_runtime_store
from jarvis_shared.schemas import error_envelope, ok_envelope
from jarvis_voice.voices import effective_voice_map, known_agents

router = APIRouter()

_CATALOG_TTL_SECONDS = 600
_catalog_cache: dict = {"at": 0.0, "models": []}


async def _kokoro_voices() -> list[str]:
    settings = get_settings()
    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            resp = await client.get(f"{settings.kokoro_tts_url}/health")
        if resp.status_code == 200:
            return list(resp.json().get("voices", []))
    except httpx.HTTPError:
        pass
    return []


async def _model_catalog() -> list[str]:
    """Live model ids from the primary provider's OpenAI-compatible /models.

    Cached for 10 minutes; empty on failure (the UI treats the catalog as a
    suggestion list, never as the validation source)."""
    if time.monotonic() - _catalog_cache["at"] < _CATALOG_TTL_SECONDS and _catalog_cache["models"]:
        return _catalog_cache["models"]
    settings = get_settings()
    if settings.default_model_provider.lower() != "nvidia" or not settings.nvidia_api_key:
        return []
    try:
        async with httpx.AsyncClient(timeout=8.0) as client:
            resp = await client.get(
                f"{settings.nvidia_base_url.rstrip('/')}/models",
                headers={"Authorization": f"Bearer {settings.nvidia_api_key}"},
            )
        if resp.status_code == 200:
            models = sorted(m.get("id", "") for m in resp.json().get("data", []) if m.get("id"))
            _catalog_cache.update(at=time.monotonic(), models=models)
            return models
    except httpx.HTTPError:
        pass
    return []


class ModelTiersBody(BaseModel):
    model_config = {"extra": "forbid"}
    # None = leave unchanged; "" = clear the override (fall back to env default)
    voice: str | None = Field(default=None, max_length=200)
    console: str | None = Field(default=None, max_length=200)


class AgentVoiceBody(BaseModel):
    model_config = {"extra": "forbid"}
    voice: str = Field(min_length=1, max_length=80)


def _model_state(store) -> dict:
    settings = get_settings()
    return {
        "voice": store.get("model.voice"),
        "console": store.get("model.console"),
        "defaults": {
            "provider": settings.default_model_provider,
            "voice_model": settings.voice_model,
            "console_model": settings.default_model_name,
        },
    }


def register_settings_routes(app) -> None:
    @router.get("/settings/model")
    async def get_model_settings(request: Request):
        require_min_role(request, "user")
        state = _model_state(get_runtime_store())
        state["catalog"] = await _model_catalog()
        return ok_envelope(state)

    @router.put("/settings/model")
    async def put_model_settings(body: ModelTiersBody, request: Request):
        p = require_min_role(request, "admin")
        store = get_runtime_store()
        changed = []
        for tier, value in (("voice", body.voice), ("console", body.console)):
            if value is None:
                continue
            if value.strip():
                store.set(f"model.{tier}", value.strip())
            else:
                store.delete(f"model.{tier}")
            changed.append(f"{tier}={value.strip() or '(default)'}")
        if changed:
            get_activity_log().record(
                "JARVIS", f"model tiers changed: {', '.join(changed)}", "completed",
                detail=f"by {p.username}",
            )
        return ok_envelope(_model_state(store))

    @router.get("/voices")
    async def get_voices(request: Request):
        require_min_role(request, "user")
        vm = effective_voice_map(get_settings())
        return ok_envelope({
            "voices": await _kokoro_voices(),
            "agents": vm["agents"],
            "default_agent": vm["default_agent"],
            "overrides": vm.get("overrides", {}),
        })

    @router.put("/agents/{agent_id}/voice")
    async def put_agent_voice(agent_id: str, body: AgentVoiceBody, request: Request):
        p = require_min_role(request, "admin")
        settings = get_settings()
        agent = agent_id.upper()
        rid = getattr(request.state, "request_id", "unknown")
        if agent not in known_agents(settings):
            return JSONResponse(status_code=404, content=error_envelope(
                "AGENT_UNKNOWN", f"'{agent}' is not a voiced agent", rid))
        catalog = await _kokoro_voices()
        if not catalog:
            return JSONResponse(status_code=503, content=error_envelope(
                "KOKORO_UNAVAILABLE", "voice catalog unavailable — Kokoro service not responding", rid))
        if body.voice not in catalog:
            return JSONResponse(status_code=400, content=error_envelope(
                "VOICE_UNKNOWN", f"'{body.voice}' is not a Kokoro voice", rid))
        get_runtime_store(settings).set(f"agent_voice.{agent}", body.voice)
        get_activity_log().record(agent, f"voice set to {body.voice}", "completed",
                                  detail=f"by {p.username} (persisted override)")
        return ok_envelope(effective_voice_map(settings))

    @router.delete("/agents/{agent_id}/voice")
    async def clear_agent_voice(agent_id: str, request: Request):
        p = require_min_role(request, "admin")
        settings = get_settings()
        agent = agent_id.upper()
        get_runtime_store(settings).delete(f"agent_voice.{agent}")
        get_activity_log().record(agent, "voice override cleared (back to voices.yaml)",
                                  "completed", detail=f"by {p.username}")
        return ok_envelope(effective_voice_map(settings))

    @router.get("/voices/preview")
    async def voice_preview(
        request: Request,
        voice: str = Query(min_length=1, max_length=80),
        text: str = Query(default="", max_length=200),
    ):
        require_min_role(request, "user")
        settings = get_settings()
        rid = getattr(request.state, "request_id", "unknown")
        catalog = await _kokoro_voices()
        if not catalog:
            return JSONResponse(status_code=503, content=error_envelope(
                "KOKORO_UNAVAILABLE", "Kokoro service not responding", rid))
        if voice not in catalog:
            return JSONResponse(status_code=400, content=error_envelope(
                "VOICE_UNKNOWN", f"'{voice}' is not a Kokoro voice", rid))
        sample = text.strip() or f"This is the {voice} voice for ECHO Command."
        try:
            async with httpx.AsyncClient(timeout=settings.kokoro_timeout_seconds) as client:
                resp = await client.post(
                    f"{settings.kokoro_tts_url}/say",
                    params={"voice": voice, "speed": "1.0"},
                    content=sample.encode("utf-8"),
                    headers={"Content-Type": "text/plain; charset=utf-8"},
                )
        except httpx.HTTPError as exc:
            return JSONResponse(status_code=502, content=error_envelope(
                "KOKORO_ERROR", f"synthesis failed: {type(exc).__name__}", rid))
        if resp.status_code != 200:
            return JSONResponse(status_code=502, content=error_envelope(
                "KOKORO_ERROR", resp.text[:200], rid))
        return Response(content=resp.content, media_type="audio/wav",
                        headers={"X-Voice": voice, "Cache-Control": "no-store"})

    app.include_router(router)
