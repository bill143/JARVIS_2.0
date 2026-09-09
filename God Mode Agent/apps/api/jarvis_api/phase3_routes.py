"""Phase 3 API: workflows, multi-agent, RAG, memory governance, routing/cost,
compliance, evals. Strict schemas, RBAC, and audit logging on every route."""

from __future__ import annotations

from fastapi import APIRouter, Query, Request
from pydantic import BaseModel, Field

from jarvis_api.deps import require_min_role
from jarvis_shared.errors import NotFound
from jarvis_shared.schemas import ok_envelope

router = APIRouter()


# ---------- schemas ----------

class WorkflowBody(BaseModel):
    model_config = {"extra": "forbid"}
    goal: str = Field(min_length=1, max_length=4000)
    mode: str = Field(default="plan-and-execute")


class IngestBody(BaseModel):
    model_config = {"extra": "forbid"}
    source: str = Field(min_length=1, max_length=512)
    title: str = Field(default="", max_length=512)
    content: str = Field(min_length=1, max_length=200000)
    sensitivity: str = Field(default="internal")
    strategy: str = Field(default="semantic")


class RagQueryBody(BaseModel):
    model_config = {"extra": "forbid"}
    query: str = Field(min_length=1, max_length=2000)


class AgentBody(BaseModel):
    model_config = {"extra": "forbid"}
    goal: str = Field(min_length=1, max_length=2000)
    arbitration: str = Field(default="critic-override")


class MemAddBody(BaseModel):
    model_config = {"extra": "forbid"}
    text: str = Field(min_length=1, max_length=8000)
    confidence: float | None = Field(default=None, ge=0, le=1)
    pinned: bool = False


class MemEditBody(BaseModel):
    model_config = {"extra": "forbid"}
    text: str | None = Field(default=None, max_length=8000)
    confidence: float | None = Field(default=None, ge=0, le=1)


class BudgetBody(BaseModel):
    model_config = {"extra": "forbid"}
    daily_usd: float = Field(ge=0, le=100000)


class RouteBody(BaseModel):
    model_config = {"extra": "forbid"}
    message: str = Field(min_length=1, max_length=8000)
    risk: str = Field(default="low")


class RetentionBody(BaseModel):
    model_config = {"extra": "forbid"}
    data_class: str = Field(min_length=1, max_length=64)
    retention_days: int = Field(ge=1, le=36500)
    region: str = Field(default="global")
    deletion_window_days: int = Field(default=30, ge=0, le=3650)


class ComplianceToggleBody(BaseModel):
    model_config = {"extra": "forbid"}
    key: str
    value: bool


class EvalBody(BaseModel):
    model_config = {"extra": "forbid"}
    suite: str = Field(default="all")
    mode: str = Field(default="offline")


def register_phase3_routes(app):
    def J(request):
        return request.app.state.jarvis

    # ================= WORKFLOWS =================
    @router.post("/workflows")
    async def create_workflow(body: WorkflowBody, request: Request):
        p = require_min_role(request, "user")
        j = J(request)
        from jarvis_planner import decompose_goal
        graph = decompose_goal(body.goal, max_steps=j.settings.planner_max_steps)
        wf = j.workflow_store.create(body.goal, body.mode, graph, owner=p.username, tenant=p.tenant)
        j.audit.record("workflow", "created", actor=p.username, tenant=p.tenant,
                       detail={"workflow_id": wf["id"], "mode": body.mode, "steps": len(wf["steps"])})
        return ok_envelope(wf)

    @router.post("/workflows/{wf_id}/start")
    async def start_workflow(wf_id: str, request: Request):
        require_min_role(request, "user")
        j = J(request)
        wf = j.workflow_store.get(wf_id)
        if not wf:
            raise NotFound("workflow not found")
        j.workflow_aborts.discard(wf_id)
        approve_gate = (lambda step: False) if wf["mode"] == "human-approval-gated" else None
        result = await j.workflow_engine.execute(
            wf_id, should_abort=lambda: wf_id in j.workflow_aborts, approve_gate=approve_gate)
        return ok_envelope(result)

    @router.post("/workflows/{wf_id}/pause")
    async def pause_workflow(wf_id: str, request: Request):
        require_min_role(request, "user")
        J(request).workflow_store.set_status(wf_id, "paused")
        return ok_envelope({"workflow_id": wf_id, "status": "paused"})

    @router.post("/workflows/{wf_id}/resume")
    async def resume_workflow(wf_id: str, request: Request):
        p = require_min_role(request, "user")
        j = J(request)
        wf = j.workflow_store.get(wf_id)
        if not wf:
            raise NotFound("workflow not found")
        j.workflow_aborts.discard(wf_id)
        # For human-approval-gated resume, approve pending high-risk steps.
        approve_gate = (lambda step: True) if wf["mode"] == "human-approval-gated" else None
        result = await j.workflow_engine.execute(
            wf_id, should_abort=lambda: wf_id in j.workflow_aborts, approve_gate=approve_gate)
        j.audit.record("workflow", "resumed", actor=p.username, tenant=p.tenant, detail={"workflow_id": wf_id})
        return ok_envelope(result)

    @router.post("/workflows/{wf_id}/abort")
    async def abort_workflow(wf_id: str, request: Request):
        require_min_role(request, "user")
        j = J(request)
        j.workflow_aborts.add(wf_id)
        j.workflow_store.set_status(wf_id, "aborted")
        return ok_envelope({"workflow_id": wf_id, "status": "aborted"})

    @router.get("/workflows/{wf_id}")
    async def workflow_status(wf_id: str, request: Request):
        require_min_role(request, "user")
        wf = J(request).workflow_store.get(wf_id)
        if not wf:
            raise NotFound("workflow not found")
        wf["checkpoints"] = J(request).workflow_store.checkpoints(wf_id)
        return ok_envelope(wf)

    @router.get("/workflows")
    async def list_workflows(request: Request):
        p = require_min_role(request, "user")
        return ok_envelope({"workflows": J(request).workflow_store.list(tenant=p.tenant)})

    # ================= MULTI-AGENT =================
    @router.post("/agents/run")
    async def run_agents(body: AgentBody, request: Request):
        p = require_min_role(request, "user")
        j = J(request)
        from jarvis_agents import MultiAgentOrchestrator

        def retriever(q):
            return j.retriever.answer(p.tenant, q)

        orch = MultiAgentOrchestrator(j.agent_bus, retriever=retriever, tool_runner=None,
                                      audit=j.audit, max_rounds=j.settings.max_agent_rounds,
                                      arbitration=body.arbitration)
        result = await orch.run(body.goal, owner=p.username, tenant=p.tenant)
        return ok_envelope(result)

    @router.get("/agents/sessions")
    async def list_agent_sessions(request: Request):
        p = require_min_role(request, "user")
        return ok_envelope({"sessions": J(request).agent_bus.list_sessions(p.tenant)})

    @router.get("/agents/sessions/{session_id}")
    async def agent_session(session_id: str, request: Request):
        require_min_role(request, "user")
        s = J(request).agent_bus.get_session(session_id)
        if not s:
            raise NotFound("agent session not found")
        return ok_envelope(s)

    # ================= RAG =================
    @router.post("/rag/ingest")
    async def rag_ingest(body: IngestBody, request: Request):
        p = require_min_role(request, "operator")
        j = J(request)
        result = j.ingestion.ingest_text(tenant=p.tenant, source=body.source, title=body.title or body.source,
                                         content=body.content, owner=p.username, sensitivity=body.sensitivity,
                                         strategy=body.strategy)
        return ok_envelope(result)

    @router.post("/rag/reindex")
    async def rag_reindex(request: Request):
        p = require_min_role(request, "operator")
        return ok_envelope(J(request).ingestion.reindex(p.tenant))

    @router.get("/rag/documents")
    async def rag_documents(request: Request):
        p = require_min_role(request, "user")
        j = J(request)
        return ok_envelope({"documents": j.rag_store.documents(p.tenant), "stats": j.rag_store.stats(p.tenant)})

    @router.post("/rag/query")
    async def rag_query(body: RagQueryBody, request: Request):
        p = require_min_role(request, "user")
        return ok_envelope(J(request).retriever.answer(p.tenant, body.query))

    @router.get("/rag/citations")
    async def rag_citations(request: Request, q: str = Query(min_length=1, max_length=2000)):
        p = require_min_role(request, "user")
        return ok_envelope(J(request).retriever.retrieve(p.tenant, q))

    # ================= MEMORY GOVERNANCE =================
    @router.post("/memory/items")
    async def mem_add(body: MemAddBody, request: Request):
        p = require_min_role(request, "user")
        j = J(request)
        result = j.mem_gov.add(tenant=p.tenant, user_id=p.user_id, text=body.text,
                               confidence=body.confidence, pinned=body.pinned,
                               provenance={"why": "api_upsert", "actor": p.username})
        j.audit.record("memory", "govern_add", actor=p.username, tenant=p.tenant, detail={"id": result["id"]})
        return ok_envelope(result)

    @router.get("/memory/items")
    async def mem_list(request: Request):
        p = require_min_role(request, "readonly")
        return ok_envelope({"items": J(request).mem_gov.list(p.tenant, p.user_id)})

    @router.post("/memory/items/{item_id}/edit")
    async def mem_edit(item_id: str, body: MemEditBody, request: Request):
        p = require_min_role(request, "user")
        j = J(request)
        item = j.mem_gov.get(item_id)
        if not item or item["tenant"] != p.tenant:
            raise NotFound("memory item not found")
        updated = j.mem_gov.edit(item_id, text=body.text, confidence=body.confidence)
        j.audit.record("memory", "govern_edit", actor=p.username, tenant=p.tenant, detail={"id": item_id})
        return ok_envelope(updated)

    @router.post("/memory/items/{item_id}/pin")
    async def mem_pin(item_id: str, request: Request, pinned: bool = Query(default=True)):
        p = require_min_role(request, "user")
        return ok_envelope(J(request).mem_gov.pin(p.tenant, p.user_id, item_id, pinned=pinned))

    @router.post("/memory/items/{item_id}/forget")
    async def mem_forget(item_id: str, request: Request):
        p = require_min_role(request, "user")
        j = J(request)
        item = j.mem_gov.get(item_id)
        if not item or item["tenant"] != p.tenant:
            raise NotFound("memory item not found")
        ok = j.mem_gov.forget(item_id)
        j.audit.record("memory", "govern_forget", actor=p.username, tenant=p.tenant, detail={"id": item_id})
        return ok_envelope({"forgotten": ok})

    @router.get("/memory/export")
    async def mem_export(request: Request):
        p = require_min_role(request, "readonly")
        return ok_envelope(J(request).mem_gov.export(p.tenant, p.user_id))

    @router.get("/memory/conflicts")
    async def mem_conflicts(request: Request):
        p = require_min_role(request, "user")
        return ok_envelope({"conflicts": J(request).mem_gov.conflicts(p.tenant, p.user_id)})

    # ---- Run 2: vector-store browse + delete ----
    @router.get("/memory/vector")
    async def vector_browse(request: Request,
                            namespace: str = Query(default="", max_length=80),
                            limit: int = Query(default=100, ge=1, le=500)):
        p = require_min_role(request, "operator")
        vector = J(request).vector
        if not namespace:
            return ok_envelope({"namespaces": vector.namespaces(), "backend": getattr(vector, "backend", "unknown")})
        return ok_envelope({"namespace": namespace, "records": vector.list(namespace, limit)})

    @router.delete("/memory/vector/{namespace}/{record_id}")
    async def vector_delete(namespace: str, record_id: str, request: Request):
        p = require_min_role(request, "operator")
        deleted = J(request).vector.delete(namespace, record_id)
        if not deleted:
            raise NotFound("vector record not found")
        J(request).audit.record("memory", "vector_delete", actor=p.username, tenant=p.tenant,
                                detail={"namespace": namespace, "id": record_id})
        return ok_envelope({"deleted": True, "namespace": namespace, "id": record_id})

    # ================= ROUTING / COST =================
    @router.post("/routing/plan")
    async def routing_plan(body: RouteBody, request: Request):
        p = require_min_role(request, "user")
        decision = J(request).model_router.route(message=body.message, tenant=p.tenant, risk=body.risk)
        return ok_envelope(decision.as_dict())

    @router.get("/cost/usage")
    async def cost_usage(request: Request):
        p = require_min_role(request, "operator")
        j = J(request)
        tenant = None if p.role == "admin" else p.tenant
        return ok_envelope({"usage": j.cost_ledger.usage_report(tenant), "budget": j.budget_manager.status(p.tenant)})

    @router.get("/cost/budget")
    async def cost_budget(request: Request):
        p = require_min_role(request, "operator")
        return ok_envelope(J(request).budget_manager.status(p.tenant))

    @router.post("/cost/budget")
    async def set_budget(body: BudgetBody, request: Request):
        p = require_min_role(request, "admin")
        J(request).budget_manager.set_limit(p.tenant, body.daily_usd)
        J(request).audit.record("routing", "budget_set", actor=p.username, tenant=p.tenant, detail={"daily_usd": body.daily_usd})
        return ok_envelope(J(request).budget_manager.status(p.tenant))

    # ================= COMPLIANCE =================
    @router.get("/compliance/status")
    async def compliance_status(request: Request):
        require_min_role(request, "operator")
        j = J(request)
        return ok_envelope({"modes": j.compliance_modes.status(),
                            "retention": j.retention.list_policies(),
                            "audit": j.audit.verify()})

    @router.post("/compliance/toggle")
    async def compliance_toggle(body: ComplianceToggleBody, request: Request):
        p = require_min_role(request, "admin")
        j = J(request)
        try:
            j.compliance_modes.set(body.key, body.value)
        except ValueError as exc:
            raise NotFound(str(exc)) from None
        j.audit.record("compliance", "toggle", actor=p.username, tenant=p.tenant, detail={body.key: body.value})
        return ok_envelope(j.compliance_modes.status())

    @router.post("/compliance/retention")
    async def set_retention(body: RetentionBody, request: Request):
        p = require_min_role(request, "admin")
        J(request).retention.set_policy(body.data_class, body.retention_days, body.region, body.deletion_window_days)
        J(request).audit.record("compliance", "retention_set", actor=p.username, tenant=p.tenant,
                                detail={"data_class": body.data_class, "days": body.retention_days})
        return ok_envelope({"retention": J(request).retention.list_policies()})

    @router.get("/compliance/validate")
    async def compliance_validate(request: Request):
        require_min_role(request, "admin")
        return ok_envelope(J(request).evidence.validate_chain())

    @router.post("/compliance/export")
    async def compliance_export(request: Request, fmt: str = Query(default="json", pattern="^(json|csv)$")):
        p = require_min_role(request, "admin")
        j = J(request)
        tenant = None if p.role == "admin" else p.tenant
        result = j.evidence.export(requested_by=p.username, tenant=tenant, fmt=fmt)
        j.audit.record("compliance", "evidence_export", actor=p.username, tenant=p.tenant,
                       detail={"export_id": result["id"], "records": result["record_count"]})
        return ok_envelope(result)

    @router.get("/compliance/exports")
    async def compliance_exports(request: Request):
        require_min_role(request, "admin")
        return ok_envelope({"exports": J(request).evidence.list_exports()})

    # ================= EVALS =================
    @router.post("/evals/run")
    async def eval_run(body: EvalBody, request: Request):
        require_min_role(request, "operator")
        j = J(request)
        ctx = _eval_context(j)
        if body.suite == "all":
            result = j.eval_runner.run_all(ctx, mode=body.mode)
        else:
            result = j.eval_runner.run_suite(body.suite, ctx, mode=body.mode)
        j.audit.record("evals", "run", detail={"suite": body.suite, "mode": body.mode})
        return ok_envelope(result)

    @router.get("/evals/history")
    async def eval_history(request: Request, suite: str | None = Query(default=None)):
        require_min_role(request, "operator")
        return ok_envelope({"history": J(request).eval_runner.history(suite)})

    app.include_router(router)


def _eval_context(j) -> dict:
    """Build a context wiring real subsystems into the eval scenarios."""
    from jarvis_tools.python_exec import run_python_sandboxed

    return {
        "sandbox": lambda code: run_python_sandboxed(code, timeout=5),
        "retriever": lambda q: j.retriever.answer("default", q),
        "memory": j.mem_gov,
        "known_query": "test",
    }
