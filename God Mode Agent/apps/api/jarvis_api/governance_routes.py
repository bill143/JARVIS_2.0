"""Governance routes: policy console, approvals, audit explorer, queue, observability."""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Query, Request
from pydantic import BaseModel, Field

from jarvis_api.deps import require_min_role
from jarvis_shared.errors import NotFound
from jarvis_shared.schemas import ok_envelope

router = APIRouter()


class PolicyRuleBody(BaseModel):
    model_config = {"extra": "forbid"}
    scope: str = Field(default="org")
    subject: str = Field(default="*")
    tool: str = Field(default="*")
    action: str = Field(default="deny")
    priority: int = Field(default=100)
    note: str = Field(default="")


class DecisionBody(BaseModel):
    model_config = {"extra": "forbid"}
    decision: str = Field(pattern="^(approved|denied)$")
    note: str = Field(default="", max_length=500)


class EnqueueBody(BaseModel):
    model_config = {"extra": "forbid"}
    kind: str = Field(min_length=1, max_length=64)
    payload: dict = Field(default_factory=dict)


def register_governance_routes(app):
    # ---- Policy console (admin) ----
    @router.get("/policy/rules")
    async def list_rules(request: Request):
        require_min_role(request, "operator")
        return ok_envelope({"rules": request.app.state.jarvis.policy.list_rules()})

    @router.post("/policy/rules")
    async def add_rule(body: PolicyRuleBody, request: Request):
        require_min_role(request, "admin")
        policy = request.app.state.jarvis.policy
        rule_id = uuid.uuid4().hex[:12]
        policy.add_rule(rule_id, body.scope, body.subject, body.tool, body.action, body.priority, body.note)
        request.app.state.jarvis.audit.record("policy", "rule_added", actor="admin",
                                               detail={"rule_id": rule_id, "action": body.action, "tool": body.tool})
        return ok_envelope({"id": rule_id})

    @router.delete("/policy/rules/{rule_id}")
    async def delete_rule(rule_id: str, request: Request):
        require_min_role(request, "admin")
        request.app.state.jarvis.policy.delete_rule(rule_id)
        request.app.state.jarvis.audit.record("policy", "rule_deleted", actor="admin", detail={"rule_id": rule_id})
        return ok_envelope({"deleted": rule_id})

    # ---- Approval queue ----
    @router.get("/approvals")
    async def list_approvals(request: Request, status: str | None = Query(default=None), limit: int = Query(default=100, ge=1, le=500)):
        principal = require_min_role(request, "operator")
        approvals = request.app.state.jarvis.approvals
        return ok_envelope({"approvals": approvals.list(status=status, tenant=principal.tenant, limit=limit)})

    @router.get("/approvals/{approval_id}")
    async def get_approval(approval_id: str, request: Request):
        require_min_role(request, "operator")
        approval = request.app.state.jarvis.approvals.get(approval_id)
        if not approval:
            raise NotFound("approval not found")
        return ok_envelope(approval)

    @router.post("/approvals/{approval_id}/decide")
    async def decide_approval(approval_id: str, body: DecisionBody, request: Request):
        principal = require_min_role(request, "operator")
        jarvis = request.app.state.jarvis
        approval = jarvis.approvals.get(approval_id)
        if not approval:
            raise NotFound("approval not found")
        updated = jarvis.approvals.decide(approval_id, body.decision, decided_by=principal.username, note=body.note)
        jarvis.audit.record("approval", body.decision, actor=principal.username, tenant=principal.tenant,
                            detail={"approval_id": approval_id, "tool": approval["tool"]})
        # If approved, execute the previously-blocked tool action and store the result.
        if body.decision == "approved":
            result = await jarvis.governed.execute_approved(updated)
            jarvis.approvals.set_result(approval_id, {"status": result.status, "output": result.output})
            return ok_envelope({"approval": jarvis.approvals.get(approval_id), "executed": True,
                                "result": {"status": result.status, "output": result.output}})
        return ok_envelope({"approval": updated, "executed": False})

    # ---- Audit explorer (role-filtered) ----
    @router.get("/audit")
    async def query_audit(request: Request, category: str | None = Query(default=None),
                          actor: str | None = Query(default=None), limit: int = Query(default=100, ge=1, le=500)):
        principal = require_min_role(request, "operator")
        # operators see their tenant; admins see everything.
        tenant = None if principal.role == "admin" else principal.tenant
        entries = request.app.state.jarvis.audit.query(category=category, actor=actor, tenant=tenant, limit=limit)
        return ok_envelope({"entries": entries})

    @router.get("/audit/verify")
    async def verify_audit(request: Request):
        require_min_role(request, "admin")
        return ok_envelope(request.app.state.jarvis.audit.verify())

    # ---- Queue ----
    @router.post("/queue/enqueue")
    async def enqueue(body: EnqueueBody, request: Request):
        principal = require_min_role(request, "user")
        job_id = request.app.state.jarvis.queue.enqueue(body.kind, body.payload, requester=principal.username)
        return ok_envelope({"job_id": job_id, "kind": body.kind})

    @router.get("/queue/jobs/{job_id}")
    async def job_status(job_id: str, request: Request):
        require_min_role(request, "user")
        job = request.app.state.jarvis.queue.get(job_id)
        if not job:
            raise NotFound("job not found")
        return ok_envelope(job)

    @router.get("/queue/stats")
    async def queue_stats(request: Request):
        require_min_role(request, "operator")
        return ok_envelope(request.app.state.jarvis.queue.stats())

    @router.get("/queue/dead")
    async def dead_letters(request: Request, limit: int = Query(default=50, ge=1, le=200)):
        require_min_role(request, "operator")
        return ok_envelope({"dead_letters": request.app.state.jarvis.queue.dead_letters(limit)})

    @router.post("/queue/process")
    async def process_one(request: Request):
        # Manual drain endpoint (operator) — the dev-all worker also drains continuously.
        require_min_role(request, "operator")
        processed = await request.app.state.jarvis.queue.process_once()
        return ok_envelope({"processed": processed})

    # ---- Observability summary (for the web dashboard) ----
    @router.get("/observability/summary")
    async def observability_summary(request: Request):
        require_min_role(request, "operator")
        jarvis = request.app.state.jarvis
        return ok_envelope({
            "metrics": jarvis.metrics.snapshot(),
            "circuit_breakers": jarvis.router.breaker_states(),
            "queue": jarvis.queue.stats(),
            "audit": jarvis.audit.verify(),
        })

    app.include_router(router)
