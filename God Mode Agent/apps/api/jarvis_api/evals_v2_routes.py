"""Evals v2 API (additive; namespaced /evals/v2/*; loaded by serve_openai).

Run/suite/case history, weighted release gate policy, reproducibility metadata,
trends, and reruns. RBAC (operator run/read, admin gate-policy), audit on
mutations, idempotency-key on run. The legacy /evals/run + /evals/history remain.
"""

from __future__ import annotations

from fastapi import Query, Request
from pydantic import BaseModel, Field

from jarvis_api.deps import require_min_role
from jarvis_reliability.idempotency import request_hash
from jarvis_shared.errors import IdempotencyConflict, JarvisError, NotFound
from jarvis_shared.schemas import ok_envelope


class RunBody(BaseModel):
    model_config = {"extra": "forbid"}
    suites: list[str] | None = None
    env_profile: str = Field(default="local", max_length=32)
    dataset_version: str = Field(default="builtin-scenarios@1", max_length=64)
    commit_sha: str = Field(default="", max_length=64)
    branch: str = Field(default="", max_length=128)
    app_version: str = Field(default="", max_length=32)


class GatePolicyBody(BaseModel):
    model_config = {"extra": "forbid"}
    block_deploy_on_fail: bool | None = None
    global_threshold: float | None = Field(default=None, ge=0, le=1)
    per_suite_threshold: dict[str, float] | None = None
    per_suite_weight: dict[str, float] | None = None
    critical_suites: list[str] | None = None


def register_evals_v2(app) -> None:
    if getattr(app.state, "_evals_v2_registered", False):
        return
    app.state._evals_v2_registered = True

    def platform(request):
        j = request.app.state.jarvis
        plat = getattr(j, "eval_platform", None)
        if plat is None:
            from jarvis_evals.platform import EvalsPlatform
            plat = EvalsPlatform(j.settings.sqlite_path, j.settings)
            j.eval_platform = plat
        return plat

    def eval_context(j) -> dict:
        from jarvis_tools.python_exec import run_python_sandboxed
        return {
            "sandbox": lambda code: run_python_sandboxed(code, timeout=5),
            "retriever": lambda q: j.retriever.answer("default", q),
            "memory": j.mem_gov,
            "known_query": "test",
        }

    def provider_meta(j) -> dict:
        s = j.settings
        return {"provider": s.default_model_provider, "fallbacks_enabled": s.enable_fallbacks}

    def resolved_models(j) -> dict:
        return {"default": j.settings.default_model_name}

    def _do_run(request, p, body: RunBody, suites):
        j = request.app.state.jarvis
        return platform(request).run(
            eval_context(j), suites=suites, triggered_by=p.username, commit_sha=body.commit_sha,
            branch=body.branch, app_version=body.app_version, env_profile=body.env_profile,
            dataset_version=body.dataset_version, provider_meta=provider_meta(j), resolved_models=resolved_models(j))

    @app.get("/evals/v2/suites")
    async def list_suites(request: Request):
        require_min_role(request, "operator")
        from jarvis_evals.platform import EvalsPlatform
        return ok_envelope({"suites": EvalsPlatform.suites_meta()})

    @app.post("/evals/v2/run")
    async def run_all(body: RunBody, request: Request):
        p = require_min_role(request, "operator")
        j = request.app.state.jarvis
        idem = request.headers.get("idempotency-key", "")
        rh = request_hash({"suites": body.suites, "env": body.env_profile})
        if idem:
            found = j.idempotency.lookup(idem, "/evals/v2/run", rh)
            if found and found["status"] == "replay":
                return ok_envelope(found["response"])
            if found and found["status"] == "conflict":
                raise IdempotencyConflict("idempotency key reused with a different payload")
        try:
            result = _do_run(request, p, body, body.suites)
        except ValueError as exc:
            raise NotFound(str(exc)) from None
        except RuntimeError as exc:
            raise JarvisError(str(exc), code="CONFLICT") from None
        j.audit.record("evals", "run", actor=p.username, tenant=p.tenant,
                       detail={"run_id": result["id"], "suites": body.suites or "all", "gate_pass": result["overall_gate_pass"]})
        if idem:
            j.idempotency.store(idem, "/evals/v2/run", rh, result)
        return ok_envelope(result)

    @app.post("/evals/v2/run/{suite}")
    async def run_one(suite: str, body: RunBody, request: Request):
        p = require_min_role(request, "operator")
        j = request.app.state.jarvis
        try:
            result = _do_run(request, p, body, [suite])
        except ValueError as exc:
            raise NotFound(str(exc)) from None
        except RuntimeError as exc:
            raise JarvisError(str(exc), code="CONFLICT") from None
        j.audit.record("evals", "run_suite", actor=p.username, tenant=p.tenant,
                       detail={"run_id": result["id"], "suite": suite})
        return ok_envelope(result)

    @app.get("/evals/v2/runs")
    async def list_runs(request: Request, limit: int = Query(default=25, ge=1, le=200),
                        offset: int = Query(default=0, ge=0), status: str | None = Query(default=None),
                        below_score: float | None = Query(default=None, ge=0, le=1),
                        suite: str | None = Query(default=None), date_from: str | None = Query(default=None),
                        date_to: str | None = Query(default=None)):
        require_min_role(request, "operator")
        return ok_envelope(platform(request).list_runs(
            limit=limit, offset=offset, status=status, below_score=below_score,
            suite=suite, date_from=date_from, date_to=date_to))

    @app.get("/evals/v2/runs/{run_id}")
    async def get_run(run_id: str, request: Request):
        require_min_role(request, "operator")
        run = platform(request).get_run(run_id)
        if not run:
            raise NotFound("run not found")
        return ok_envelope(run)

    @app.get("/evals/v2/runs/{run_id}/suites")
    async def get_run_suites(run_id: str, request: Request):
        require_min_role(request, "operator")
        run = platform(request).get_run(run_id)
        if not run:
            raise NotFound("run not found")
        return ok_envelope({"suites": run["suites"]})

    @app.get("/evals/v2/runs/{run_id}/suites/{suite}/cases")
    async def get_cases(run_id: str, suite: str, request: Request, only_failed: bool = Query(default=False)):
        require_min_role(request, "operator")
        return ok_envelope({"cases": platform(request).case_results(run_id, suite, only_failed=only_failed)})

    @app.get("/evals/v2/trends")
    async def trends(request: Request, suite: str | None = Query(default=None), limit: int = Query(default=50, ge=1, le=500)):
        require_min_role(request, "operator")
        return ok_envelope(platform(request).trends(suite=suite, limit=limit))

    @app.get("/evals/v2/gate-policy")
    async def get_gate_policy(request: Request):
        require_min_role(request, "operator")
        return ok_envelope(platform(request).gate_policy())

    @app.put("/evals/v2/gate-policy")
    async def put_gate_policy(body: GatePolicyBody, request: Request):
        p = require_min_role(request, "admin")
        j = request.app.state.jarvis
        updated = platform(request).set_gate_policy(
            block_deploy_on_fail=body.block_deploy_on_fail, global_threshold=body.global_threshold,
            per_suite_threshold=body.per_suite_threshold, per_suite_weight=body.per_suite_weight,
            critical_suites=body.critical_suites, updated_by=p.username)
        j.audit.record("evals", "gate_policy_update", actor=p.username, tenant=p.tenant,
                       detail={"block_deploy_on_fail": updated["block_deploy_on_fail"],
                               "global_threshold": updated["global_threshold"]})
        return ok_envelope(updated)

    @app.post("/evals/v2/runs/{run_id}/rerun")
    async def rerun(run_id: str, request: Request):
        p = require_min_role(request, "operator")
        j = request.app.state.jarvis
        result = platform(request).rerun(eval_context(j), triggered_by=p.username) if False else None
        # rerun needs the original run's metadata; call platform.rerun with context.
        result = platform(request).rerun(run_id, eval_context(j), triggered_by=p.username)
        if not result:
            raise NotFound("run not found")
        j.audit.record("evals", "rerun", actor=p.username, tenant=p.tenant,
                       detail={"source_run": run_id, "new_run": result["id"]})
        return ok_envelope(result)
