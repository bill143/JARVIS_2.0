"""Compliance management extension routes (additive; loaded by serve_openai).

Turns the Compliance tab into a real management console:
- mode changes that REQUIRE an audit reason (segregation + accountability),
- retention policy add/edit/delete via an approval workflow (change requests),
- evidence export with scope/date/category filters that the UI can download,
- export job re-download, and an audit-chain validation report.

main.py and phase3_routes.py are untouched. register_compliance_ext() attaches
these routes to an existing app and is idempotent.
"""

from __future__ import annotations

from fastapi import Query, Request
from pydantic import BaseModel, Field, model_validator

from jarvis_api.deps import require_min_role
from jarvis_shared.errors import JarvisError, NotFound
from jarvis_shared.schemas import ok_envelope


class ModeChangeBody(BaseModel):
    model_config = {"extra": "forbid"}
    key: str = Field(min_length=1, max_length=64)
    value: bool
    reason: str = Field(min_length=3, max_length=500)


class RetentionRequestBody(BaseModel):
    model_config = {"extra": "forbid"}
    op: str = Field(default="upsert", pattern="^(upsert|delete)$")
    data_class: str = Field(min_length=1, max_length=64)
    retention_days: int | None = Field(default=None, ge=1, le=36500)
    region: str = Field(default="global", max_length=32)
    deletion_window_days: int | None = Field(default=None, ge=0, le=3650)
    reason: str = Field(min_length=3, max_length=500)

    @model_validator(mode="after")
    def _require_days_on_upsert(self):
        if self.op == "upsert" and self.retention_days is None:
            raise ValueError("retention_days is required for an upsert request")
        return self


class RetentionDecisionBody(BaseModel):
    model_config = {"extra": "forbid"}
    decision: str = Field(pattern="^(approve|deny)$")
    reason: str = Field(default="", max_length=500)


def register_compliance_ext(app) -> None:
    if getattr(app.state, "_compliance_ext_registered", False):
        return
    app.state._compliance_ext_registered = True

    def J(request):
        return request.app.state.jarvis

    @app.get("/compliance/meta")
    async def compliance_meta(request: Request):
        require_min_role(request, "operator")
        j = J(request)
        return ok_envelope({"modes_detail": j.compliance_modes.describe(),
                            "regions": j.retention.regions()})

    @app.post("/compliance/mode")
    async def compliance_mode(body: ModeChangeBody, request: Request):
        p = require_min_role(request, "admin")
        j = J(request)
        try:
            j.compliance_modes.set(body.key, body.value)
        except ValueError as exc:
            raise NotFound(str(exc)) from None
        j.audit.record("compliance", "mode_change", actor=p.username, tenant=p.tenant,
                       detail={"key": body.key, "value": body.value, "reason": body.reason})
        return ok_envelope({"modes": j.compliance_modes.status(),
                            "modes_detail": j.compliance_modes.describe()})

    @app.get("/compliance/audit/summary")
    async def audit_summary(request: Request):
        require_min_role(request, "admin")
        return ok_envelope(J(request).evidence.validation_report())

    # ---------- evidence export (filters + browser download) ----------
    @app.post("/compliance/evidence/export")
    async def evidence_export(request: Request,
                              fmt: str = Query(default="json", pattern="^(json|csv)$"),
                              scope: str = Query(default="all", pattern="^(all|tenant)$"),
                              start: str | None = Query(default=None, max_length=40),
                              end: str | None = Query(default=None, max_length=40),
                              category: str | None = Query(default=None, max_length=64)):
        p = require_min_role(request, "admin")
        j = J(request)
        tenant = None if scope == "all" else p.tenant
        result = j.evidence.export(requested_by=p.username, tenant=tenant, fmt=fmt,
                                   start=start, end=end, category=category)
        j.audit.record("compliance", "evidence_export", actor=p.username, tenant=p.tenant,
                       detail={"export_id": result["id"], "records": result["record_count"],
                               "fmt": fmt, "scope": scope, "start": start, "end": end, "category": category})
        payload = j.evidence.read_export(result["id"]) or {"filename": "", "content": ""}
        return ok_envelope({**result, "filename": payload["filename"], "content": payload["content"]})

    @app.get("/compliance/evidence/exports/{export_id}/download")
    async def evidence_download(export_id: str, request: Request):
        require_min_role(request, "admin")
        payload = J(request).evidence.read_export(export_id)
        if not payload:
            raise NotFound("export not found (file may have been cleaned up)")
        return ok_envelope(payload)

    # ---------- retention change requests + approval workflow ----------
    @app.post("/compliance/retention/request")
    async def retention_request(body: RetentionRequestBody, request: Request):
        p = require_min_role(request, "admin")
        j = J(request)
        req = j.retention.create_request(
            requested_by=p.username, op=body.op, data_class=body.data_class,
            retention_days=body.retention_days, region=body.region,
            deletion_window_days=body.deletion_window_days, reason=body.reason)
        j.audit.record("compliance", "retention_change_requested", actor=p.username, tenant=p.tenant,
                       detail={"request_id": req["id"], "op": body.op, "data_class": body.data_class,
                               "reason": body.reason})
        return ok_envelope(req)

    @app.get("/compliance/retention/requests")
    async def retention_requests(request: Request, status: str | None = Query(default=None)):
        require_min_role(request, "admin")
        return ok_envelope({"requests": J(request).retention.list_requests(status=status)})

    @app.post("/compliance/retention/requests/{req_id}/decide")
    async def retention_decide(req_id: str, body: RetentionDecisionBody, request: Request):
        p = require_min_role(request, "admin")
        j = J(request)
        req = j.retention.get_request(req_id)
        if not req:
            raise NotFound("retention change request not found")
        if req["status"] != "pending":
            raise JarvisError(f"request already {req['status']}", code="CONFLICT")
        updated = j.retention.decide_request(req_id, decided_by=p.username,
                                             decision=body.decision, decision_reason=body.reason)
        j.audit.record("compliance", "retention_change_decided", actor=p.username, tenant=p.tenant,
                       detail={"request_id": req_id, "decision": body.decision, "reason": body.reason})
        return ok_envelope({"request": updated, "retention": j.retention.list_policies()})
