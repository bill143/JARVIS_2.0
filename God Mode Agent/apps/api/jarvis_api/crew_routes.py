"""Crew routes (ECHO Command agent map v3): DIRECTOR-managed worker tasks."""

from __future__ import annotations

from fastapi import APIRouter, Request
from pydantic import BaseModel, Field

from jarvis_agents.crew import WORKERS, CrewManager, load_agent_registry
from jarvis_api.deps import require_min_role
from jarvis_shared.schemas import ok_envelope

router = APIRouter()


class CrewTaskBody(BaseModel):
    model_config = {"extra": "forbid"}
    worker: str = Field(min_length=1, max_length=32)
    task: str = Field(min_length=1, max_length=4000)
    acceptance_criteria: str = Field(min_length=1, max_length=4000)


def register_crew_routes(app) -> None:
    @router.get("/crew/workers")
    async def crew_workers(request: Request):
        require_min_role(request, "user")
        registry = load_agent_registry()
        return ok_envelope({"workers": [
            {"name": w, "role": registry.get(w, {}).get("role", ""),
             "reports_to": registry.get(w, {}).get("reports_to", "")}
            for w in WORKERS
        ]})

    @router.post("/crew/tasks")
    async def crew_task(body: CrewTaskBody, request: Request):
        principal = require_min_role(request, "operator")
        manager = CrewManager(request.app.state.jarvis.router)
        try:
            result = await manager.run_task(
                body.worker.upper(), body.task, body.acceptance_criteria,
                requested_by=principal.username,
            )
        except ValueError as exc:  # non-crew worker (incl. TRADER isolation)
            from fastapi.responses import JSONResponse

            from jarvis_shared.schemas import error_envelope
            rid = getattr(request.state, "request_id", "unknown")
            return JSONResponse(status_code=400,
                                content=error_envelope("CREW_WORKER_INVALID", str(exc), rid))
        return ok_envelope(result)

    app.include_router(router)
