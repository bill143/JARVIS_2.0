"""Integrations catalog (v1 scaffold; additive; loaded by serve_openai).

Read-only connector/integration catalog assembled from live config:
- AI providers (configured? from settings keys),
- enabled tools (from the tool registry),
- planned integrations (clearly labeled scaffold, not yet wired).

Key management and real connector wiring arrive with the Settings batch; this
endpoint intentionally exposes no fake mutating actions.
"""

from __future__ import annotations

from fastapi import Request

from jarvis_api.deps import require_min_role
from jarvis_shared.schemas import ok_envelope

PLANNED = ["Slack", "GitHub", "Google Drive", "Notion", "Outbound Webhook"]


def register_integrations(app) -> None:
    if getattr(app.state, "_integrations_registered", False):
        return
    app.state._integrations_registered = True

    @app.get("/integrations/catalog")
    async def catalog(request: Request):
        require_min_role(request, "user")
        j = request.app.state.jarvis
        s = j.settings
        providers = [
            {"name": "OpenAI", "category": "AI Provider",
             "status": "connected" if s.openai_api_key else "available", "hint": "Set OPENAI_API_KEY in .env"},
            {"name": "Anthropic", "category": "AI Provider",
             "status": "connected" if s.anthropic_api_key else "available", "hint": "Set ANTHROPIC_API_KEY in .env"},
            {"name": "Deepgram (STT)", "category": "AI Provider",
             "status": "connected" if s.deepgram_api_key else "available", "hint": "Set DEEPGRAM_API_KEY in .env"},
            {"name": "ElevenLabs (TTS)", "category": "AI Provider",
             "status": "connected" if s.elevenlabs_api_key else "available", "hint": "Set ELEVENLABS_API_KEY in .env"},
        ]
        tools = [{"name": t, "category": "Tool", "status": "enabled", "hint": "built-in tool"} for t in j.registry.names()]
        planned = [{"name": n, "category": "Planned", "status": "planned", "hint": "catalog scaffold — not yet wired"} for n in PLANNED]
        return ok_envelope({
            "integrations": providers + tools + planned,
            "counts": {"providers": len(providers), "tools": len(tools), "planned": len(planned)},
        })
