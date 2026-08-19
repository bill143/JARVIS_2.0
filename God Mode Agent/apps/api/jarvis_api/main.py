"""JARVIS FastAPI backend — Phase 2 hardened.

Adds JWT auth + RBAC, a policy engine on every tool call, an approval workflow,
prompt-injection defenses, idempotency, a durable queue, Prometheus metrics,
OpenTelemetry tracing, and a hash-chained governance audit log — while
preserving all Phase 1 endpoints and behavior.
"""

from __future__ import annotations

# Use the Windows certificate store for TLS verification. Local AV/proxy HTTPS
# inspection (e.g. Norton Web/Mail Shield) re-signs traffic with a root CA that
# certifi rejects but the OS store trusts; without this every provider call
# fails CERTIFICATE_VERIFY_FAILED and the router silently degrades to mock.
try:
    import truststore

    truststore.inject_into_ssl()
except ImportError:
    pass

import base64
import uuid
from types import SimpleNamespace

from fastapi import FastAPI, Query, Request, WebSocket, WebSocketDisconnect
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, PlainTextResponse

from jarvis_adapters.router import build_router
from jarvis_api.auth_routes import register_auth_routes
from jarvis_api.deps import PrincipalBoundTools, resolve_principal
from jarvis_api.governance_routes import register_governance_routes
from jarvis_api.rate_limit import RateLimiter
from jarvis_auth.service import AuthService
from jarvis_core.agent import AgentLoop
from jarvis_memory.metadata import MetadataStore
from jarvis_memory.short_term import ContextBuffer
from jarvis_memory.vector_store import get_vector_store, make_namespace
from jarvis_observability.audit_chain import AuditChain
from jarvis_observability.metrics import estimate_cost, get_metrics
from jarvis_observability.tracing import setup_tracing, trace_span
from jarvis_policy.approvals import ApprovalStore
from jarvis_policy.engine import PolicyEngine
from jarvis_policy.governed import ApprovalRequired, GovernedToolRegistry
from jarvis_reliability.idempotency import IdempotencyStore, request_hash
from jarvis_reliability.queue import get_queue
from jarvis_shared.config import Settings, get_settings
from jarvis_shared.errors import IdempotencyConflict, JarvisError, PromptInjectionBlocked
from jarvis_shared.logging import (
    get_logger,
    log_event,
    request_id_var,
    session_id_var,
    user_id_var,
)
from jarvis_shared.migrate import migrate_up
from jarvis_shared.schemas import (
    ChatRequest,
    MemoryUpsertRequest,
    ToolExecuteRequest,
    error_envelope,
    ok_envelope,
)
from jarvis_tools import AuditLog, build_default_registry
from jarvis_vision.analyzer import analyze_image_bytes
from jarvis_voice.loop import VoiceSession

logger = get_logger("jarvis.api")

API_VERSION = "0.3.0"

SECURITY_HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    "X-XSS-Protection": "1; mode=block",
    "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'",
    "Strict-Transport-Security": "max-age=31536000; includeSubDomains",
}


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()

    # --- migrations (idempotent; preserves Phase 1 data) ---
    migrate_up(settings.sqlite_path)

    setup_tracing(enabled=settings.otel_enabled, exporter=settings.otel_exporter, endpoint=settings.otel_endpoint)
    metrics = get_metrics()

    app = FastAPI(title="JARVIS God Mode Agent API", version=API_VERSION)

    # --- core services ---
    audit_tool = AuditLog(settings.sqlite_path)
    metadata = MetadataStore(settings.sqlite_path)
    audit = AuditChain(settings.sqlite_path, enable_hash_chain=settings.enable_audit_log_hash_chain)
    auth = AuthService(settings, settings.sqlite_path)
    auth.ensure_seed_admin("admin", "admin123")
    registry = build_default_registry(settings, audit=audit_tool)
    policy = PolicyEngine(settings, settings.sqlite_path)
    approvals = ApprovalStore(settings.sqlite_path)
    governed = GovernedToolRegistry(registry, policy, approvals, audit, metrics=metrics)
    router = build_router(settings, metrics=metrics)
    context = ContextBuffer()
    vector = get_vector_store(settings.chroma_path)
    idempotency = IdempotencyStore(settings.sqlite_path, ttl_hours=settings.idempotency_ttl_hours)
    queue = get_queue(settings, settings.sqlite_path, metrics=metrics)
    limiter = RateLimiter(settings.rate_limit_per_min)
    auth_limiter = RateLimiter(max(5, settings.auth_max_failed_attempts * 2))
    ws_conn_counts: dict[str, int] = {}

    def base_agent() -> AgentLoop:
        return AgentLoop(router, registry, context=context, max_iterations=settings.max_tool_iterations,
                         injection_threshold=settings.injection_block_threshold)

    # queue handlers for long-running jobs
    def _handle_vision_batch(payload: dict) -> dict:
        results = []
        for item in payload.get("frames", []):
            try:
                data = base64.b64decode(item)
                results.append(analyze_image_bytes(data, source="batch", tesseract_cmd=settings.tesseract_cmd).model_dump())
            except Exception as exc:
                results.append({"ok": False, "note": str(exc)})
        return {"analyzed": len(results), "results": results}

    def _handle_echo(payload: dict) -> dict:
        if payload.get("fail"):
            raise RuntimeError("intentional failure for DLQ/retry testing")
        return {"echo": payload.get("value", "")}

    queue.register("vision_batch", _handle_vision_batch)
    queue.register("echo", _handle_echo)

    # --- Phase 3 services ---
    from jarvis_agents import MessageBus
    from jarvis_compliance import ComplianceModes, EvidenceExporter, RetentionManager
    from jarvis_evals import EvalRunner
    from jarvis_memory.governance import MemoryGovernanceStore
    from jarvis_planner import WorkflowEngine, WorkflowStore
    from jarvis_rag import HybridRetriever, IngestionPipeline, RagStore
    from jarvis_routing import BudgetManager, CostLedger, ModelRouter, ResponseCache

    workflow_store = WorkflowStore(settings.sqlite_path)
    agent_bus = MessageBus(settings.sqlite_path)
    rag_store = RagStore(settings.sqlite_path)
    ingestion = IngestionPipeline(rag_store, settings, audit=audit)
    retriever = HybridRetriever(rag_store, settings, metrics=metrics)
    mem_gov = MemoryGovernanceStore(settings.sqlite_path, settings)
    cost_ledger = CostLedger(settings.sqlite_path)
    budget_manager = BudgetManager(settings.sqlite_path, cost_ledger,
                                   default_daily_usd=settings.routing_budget_daily_usd)
    response_cache = ResponseCache(settings.sqlite_path, ttl_sec=settings.cache_ttl_sec)
    model_router = ModelRouter(settings, budget_manager=budget_manager)
    retention = RetentionManager(settings.sqlite_path, settings)
    evidence = EvidenceExporter(settings.sqlite_path, audit, settings.evidence_export_path)
    compliance_modes = ComplianceModes(settings)
    eval_runner = EvalRunner(settings.sqlite_path, settings)
    workflow_aborts: set[str] = set()

    async def _workflow_step_runner(step: dict):
        """Execute a planned step: tool actions via the mock-admin governed tools,
        reason/synthesize deterministically."""
        action = step["action"]
        if action.startswith("tool:"):
            tool = action.split(":", 1)[1]
            result = await registry.execute(tool, step["arguments"], session_id="workflow")
            return ("completed" if result.status == "ok" else "failed", result.output)
        if action == "reason":
            return ("completed", {"reasoned": step["arguments"].get("prompt", "")[:200]})
        return ("completed", {"synthesized": step["arguments"].get("goal", "")[:200]})

    workflow_engine = WorkflowEngine(workflow_store, _workflow_step_runner, audit=audit, metrics=metrics)

    app.state.jarvis = SimpleNamespace(
        settings=settings, audit=audit, audit_tool=audit_tool, metadata=metadata, auth=auth,
        registry=registry, governed=governed, policy=policy, approvals=approvals, router=router,
        vector=vector, context=context, idempotency=idempotency, queue=queue, metrics=metrics,
        base_agent=base_agent,
        # Phase 3
        workflow_store=workflow_store, workflow_engine=workflow_engine, workflow_aborts=workflow_aborts,
        agent_bus=agent_bus, rag_store=rag_store, ingestion=ingestion, retriever=retriever,
        mem_gov=mem_gov, cost_ledger=cost_ledger, budget_manager=budget_manager,
        response_cache=response_cache, model_router=model_router, retention=retention,
        evidence=evidence, compliance_modes=compliance_modes, eval_runner=eval_runner,
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_allowed_list,
        allow_credentials=False,
        allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type", "X-API-Key", "Idempotency-Key"],
    )

    max_body_bytes = int(settings.max_request_body_mb * 1024 * 1024)

    @app.middleware("http")
    async def request_context(request: Request, call_next):
        request_id = uuid.uuid4().hex[:12]
        request.state.request_id = request_id
        token = request_id_var.set(request_id)
        try:
            path = request.url.path
            # body size limit
            cl = request.headers.get("content-length")
            if cl and cl.isdigit() and int(cl) > max_body_bytes:
                return JSONResponse(status_code=413, content=error_envelope("PAYLOAD_TOO_LARGE", f"body exceeds {settings.max_request_body_mb}MB", request_id))
            # rate limiting (auth endpoints use a stricter limiter for brute-force protection)
            if path not in ("/health", "/metrics"):
                client_key = request.client.host if request.client else "unknown"
                active_limiter = auth_limiter if path.startswith("/auth/login") else limiter
                allowed, retry_after = active_limiter.allow(f"{client_key}:{path if path.startswith('/auth/login') else ''}")
                if not allowed:
                    metrics.counter("jarvis_rate_limited_total", labels={"path": path})
                    return JSONResponse(status_code=429, content=error_envelope("RATE_LIMITED", "Too many requests", request_id),
                                        headers={"Retry-After": str(int(retry_after))})
            with trace_span("http.request", path=path, method=request.method):
                response = await call_next(request)
            response.headers["X-Request-Id"] = request_id
            for k, v in SECURITY_HEADERS.items():
                response.headers[k] = v
            metrics.counter("jarvis_http_requests_total", labels={"path": path, "status": str(response.status_code)})
            log_event(logger, "http.request", request_id=request_id, path=path, method=request.method, status=response.status_code)
            return response
        finally:
            request_id_var.reset(token)

    @app.exception_handler(JarvisError)
    async def jarvis_error_handler(request: Request, exc: JarvisError):
        rid = getattr(request.state, "request_id", "unknown")
        payload = error_envelope(exc.code, exc.message, rid)
        if isinstance(exc, ApprovalRequired):
            payload["error"]["approval_id"] = exc.approval_id
        return JSONResponse(status_code=exc.http_status, content=payload)

    @app.exception_handler(RequestValidationError)
    async def validation_handler(request: Request, exc: RequestValidationError):
        rid = getattr(request.state, "request_id", "unknown")
        detail = "; ".join(f"{'.'.join(str(p) for p in e['loc'])}: {e['msg']}" for e in exc.errors()[:5])
        return JSONResponse(status_code=400, content=error_envelope("VALIDATION_ERROR", detail, rid))

    @app.exception_handler(Exception)
    async def unhandled_handler(request: Request, exc: Exception):
        rid = getattr(request.state, "request_id", "unknown")
        logger.exception("unhandled error request_id=%s", rid)
        return JSONResponse(status_code=500, content=error_envelope("INTERNAL_ERROR", "Unexpected server error", rid))

    # ---------------- public ----------------

    @app.on_event("startup")
    async def _start_worker():
        # Auto-drain the durable queue in the background (disabled in tests, which
        # drive the queue deterministically via POST /queue/process).
        if settings.queue_autostart:
            import asyncio

            app.state.worker_task = asyncio.create_task(queue.run_worker())

    @app.get("/health")
    async def health():
        return ok_envelope({
            "status": "ok", "version": API_VERSION, "phase": 3,
            "providers": {
                "nvidia": bool(settings.nvidia_api_key),
                "openai": bool(settings.openai_api_key), "anthropic": bool(settings.anthropic_api_key),
                "deepgram": bool(settings.deepgram_api_key), "elevenlabs": bool(settings.elevenlabs_api_key),
            },
            "default_provider": settings.default_model_provider, "default_model": settings.default_model_name,
            "router_order": [a.name for a in router.adapters],
            "fallbacks_enabled": settings.enable_fallbacks, "memory_backend": getattr(vector, "backend", "unknown"),
            "queue_backend": queue.backend, "auth_mode": settings.auth_mode,
            "dev_auth_bypass": settings.allow_dev_auth_bypass, "policy_default": settings.policy_default_action,
            "tools": registry.names(),
        })

    @app.get("/metrics")
    async def prometheus_metrics():
        if not settings.prometheus_enabled:
            return PlainTextResponse("# metrics disabled\n", media_type="text/plain")
        # refresh dynamic gauges
        stats = queue.stats()
        metrics.gauge("jarvis_queue_depth", stats["depth"])
        metrics.gauge("jarvis_queue_dead", stats["dead"])
        return PlainTextResponse(metrics.render(), media_type="text/plain; version=0.0.4")

    register_auth_routes(app)
    register_governance_routes(app)
    from jarvis_api.phase3_routes import register_phase3_routes
    register_phase3_routes(app)

    @app.get("/observability/slo")
    async def slo():
        report = cost_ledger.usage_report()
        snap = metrics.snapshot()
        latencies = [v.get("avg", 0) for k, v in snap.get("histograms", {}).items() if "duration" in k or "latency" in k]
        avg_latency = round(sum(latencies) / len(latencies), 2) if latencies else 0.0
        tool_calls = sum(v for k, v in snap.get("counters", {}).items() if k.startswith("jarvis_tool_calls_total"))
        tool_ok = sum(v for k, v in snap.get("counters", {}).items() if "jarvis_tool_calls_total" in k and 'status="ok"' in k)
        return ok_envelope({
            "latency": {"avg_ms": avg_latency, "target_ms": settings.routing_latency_target_ms},
            "tool_success_rate": round(tool_ok / tool_calls, 4) if tool_calls else 1.0,
            "cost": report["totals"],
            "queue": queue.stats(),
            "rag": {"hit_rate": snap.get("gauges", {}).get("jarvis_rag_hit_rate", 0.0),
                    "citation_coverage": snap.get("gauges", {}).get("jarvis_rag_citation_coverage", 0.0)},
        })

    # ---------------- chat ----------------

    @app.post("/chat")
    async def chat(body: ChatRequest, request: Request):
        principal = resolve_principal(request)
        session_id = f"{principal.tenant}:{body.session_id}"
        session_id_var.set(session_id)
        user_id_var.set(principal.user_id)
        metadata.touch_session(session_id, principal.user_id)

        agent = base_agent()
        agent.registry = PrincipalBoundTools(governed, principal)
        with trace_span("agent.chat", user=principal.username):
            try:
                result = await agent.run(body.message, session_id=session_id, user_id=principal.user_id)
            except PromptInjectionBlocked as exc:
                audit.record("safety", "injection_blocked", actor=principal.username, tenant=principal.tenant,
                             detail={"message_preview": body.message[:120]})
                raise exc
        for e in result.tool_events:
            metrics.counter("jarvis_tool_calls_total", labels={"tool": e.tool, "status": e.status})
            metrics.observe("jarvis_tool_duration_ms", e.duration_ms, labels={"tool": e.tool})
        tokens = max((len(body.message) + len(result.reply)) // 4, 1)
        metrics.counter("jarvis_model_tokens_total", value=tokens, labels={"model": result.model})
        metrics.gauge("jarvis_model_cost_estimate_usd", estimate_cost(result.model, len(result.reply) // 4))
        # Phase 3: cost accounting per tenant/user/provider/model
        cost_ledger.record(tenant=principal.tenant, user_id=principal.user_id, provider=result.provider,
                           model=result.model, task_type="chat", tokens=tokens, latency_ms=0.0)
        metadata.log_session_event(session_id, "chat", {"provider": result.provider, "iterations": result.iterations,
                                                          "tools_used": [e.tool for e in result.tool_events]})
        return ok_envelope({
            "reply": result.reply, "provider": result.provider, "model": result.model,
            "iterations": result.iterations, "tool_events": [e.model_dump() for e in result.tool_events],
            "session_id": session_id,
        })

    # ---------------- tools (policy + idempotency) ----------------

    @app.post("/tools/execute")
    async def tools_execute(body: ToolExecuteRequest, request: Request):
        principal = resolve_principal(request)
        idem_key = request.headers.get("idempotency-key", "")
        req_hash = request_hash({"tool": body.tool, "arguments": body.arguments, "user": principal.user_id})
        if idem_key:
            found = idempotency.lookup(idem_key, "/tools/execute", req_hash)
            if found and found["status"] == "replay":
                return ok_envelope(found["response"])
            if found and found["status"] == "conflict":
                raise IdempotencyConflict("idempotency key reused with different payload")

        result, decision = await governed.execute(principal, body.tool, body.arguments, session_id=f"{principal.tenant}:{body.session_id}")
        metrics.counter("jarvis_tool_calls_total", labels={"tool": body.tool, "status": result.status})
        data = {"tool": result.tool, "status": result.status, "output": result.output,
                "duration_ms": result.duration_ms, "policy": decision.as_dict()}
        if idem_key:
            idempotency.store(idem_key, "/tools/execute", req_hash, data)
        return ok_envelope(data)

    # ---------------- memory (tenant-scoped + idempotency) ----------------

    @app.get("/memory/search")
    async def memory_search(request: Request, q: str = Query(min_length=1, max_length=2000),
                            user_id: str = Query(default="default", max_length=128),
                            session_id: str | None = Query(default=None, max_length=128),
                            k: int = Query(default=5, ge=1, le=50)):
        principal = resolve_principal(request)
        namespace = make_namespace(f"{principal.tenant}--{user_id}", session_id)
        hits = vector.search(namespace, q, k=k)
        return ok_envelope({"namespace": namespace, "backend": getattr(vector, "backend", "unknown"), "hits": hits})

    @app.post("/memory/upsert")
    async def memory_upsert(body: MemoryUpsertRequest, request: Request):
        principal = resolve_principal(request)
        idem_key = request.headers.get("idempotency-key", "")
        req_hash = request_hash({"text": body.text, "user_id": body.user_id, "user": principal.user_id})
        if idem_key:
            found = idempotency.lookup(idem_key, "/memory/upsert", req_hash)
            if found and found["status"] == "replay":
                return ok_envelope(found["response"])
            if found and found["status"] == "conflict":
                raise IdempotencyConflict("idempotency key reused with different payload")
        namespace = make_namespace(f"{principal.tenant}--{body.user_id}", body.session_id or None)
        record_id = body.record_id or uuid.uuid4().hex[:16]
        vector.upsert(namespace, record_id, body.text, body.metadata)
        audit.record("memory", "upsert", actor=principal.username, tenant=principal.tenant, detail={"id": record_id, "namespace": namespace})
        data = {"id": record_id, "namespace": namespace}
        if idem_key:
            idempotency.store(idem_key, "/memory/upsert", req_hash, data)
        return ok_envelope(data)

    @app.get("/sessions/{session_id}/logs")
    async def session_logs(session_id: str, request: Request, limit: int = Query(default=100, ge=1, le=500)):
        principal = resolve_principal(request)
        scoped = session_id if session_id.startswith(f"{principal.tenant}:") else f"{principal.tenant}:{session_id}"
        # accept both raw and tenant-scoped ids for Phase 1 compatibility
        audit_entries = audit_tool.for_session(scoped, limit=limit) or audit_tool.for_session(session_id, limit=limit)
        events = metadata.get_session_logs(scoped, limit=limit) or metadata.get_session_logs(session_id, limit=limit)
        return ok_envelope({"session_id": session_id, "audit": audit_entries, "events": events})

    @app.post("/vision/analyze")
    async def vision_analyze(body: dict, request: Request):
        principal = resolve_principal(request)
        image_b64 = body.get("image_b64", "")
        source = str(body.get("source", "upload"))[:64]
        session_id = f"{principal.tenant}:{str(body.get('session_id', 'default'))[:96]}"
        if not image_b64 or not isinstance(image_b64, str):
            raise JarvisError("image_b64 is required", code="VALIDATION_ERROR")
        try:
            data = base64.b64decode(image_b64)
        except Exception:
            raise JarvisError("image_b64 is not valid base64", code="VALIDATION_ERROR") from None
        if len(data) > 10 * 1024 * 1024:
            raise JarvisError("image exceeds 10MB limit", code="PAYLOAD_TOO_LARGE")
        analysis = analyze_image_bytes(data, source=source, tesseract_cmd=settings.tesseract_cmd)
        # sanitize OCR text (untrusted) before returning / logging
        from jarvis_safety.sanitizer import sanitize_untrusted

        payload = analysis.model_dump()
        payload["ocr_text"] = sanitize_untrusted(payload.get("ocr_text", ""), max_len=4000)
        metadata.log_session_event(session_id, "vision.analysis", payload)
        return ok_envelope(payload)

    # ---------------- websockets (auth handshake + heartbeat + conn limit) ----------------

    def _ws_authenticate(ws: WebSocket):
        token = ws.query_params.get("token", "")
        api_key = ws.query_params.get("api_key", "")
        if token:
            return auth.principal_from_access(token)
        if api_key:
            return auth.principal_from_api_key(api_key)
        if settings.allow_dev_auth_bypass:
            return auth.dev_bypass_principal()
        return None

    async def _ws_guard(ws: WebSocket):
        ip = ws.client.host if ws.client else "unknown"
        if ws_conn_counts.get(ip, 0) >= settings.ws_max_connections_per_ip:
            await ws.close(code=1013)  # try again later
            return None, None
        try:
            principal = _ws_authenticate(ws)
        except JarvisError:
            principal = None
        if principal is None:
            await ws.close(code=4401)  # unauthorized
            return None, None
        await ws.accept()
        ws_conn_counts[ip] = ws_conn_counts.get(ip, 0) + 1
        metrics.gauge("jarvis_ws_connections", sum(ws_conn_counts.values()))
        return principal, ip

    def _release(ip: str | None):
        if ip and ws_conn_counts.get(ip):
            ws_conn_counts[ip] -= 1
            metrics.gauge("jarvis_ws_connections", sum(ws_conn_counts.values()))

    @app.websocket("/realtime/chat")
    async def ws_chat(ws: WebSocket):
        principal, ip = await _ws_guard(ws)
        if principal is None:
            return
        agent = base_agent()
        agent.registry = PrincipalBoundTools(governed, principal)
        try:
            while True:
                incoming = await ws.receive_json()
                if incoming.get("type") == "ping":
                    await ws.send_json({"type": "pong"})
                    continue
                message = str(incoming.get("message", "")).strip()
                session_id = f"{principal.tenant}:{str(incoming.get('session_id', 'default'))[:96]}"
                if not message:
                    await ws.send_json({"type": "error", "message": "message is required"})
                    continue

                async def on_event(event):
                    await ws.send_json({"type": "tool_event", **event.model_dump()})

                try:
                    result = await agent.run(message, session_id=session_id, on_event=on_event)
                except PromptInjectionBlocked as exc:
                    await ws.send_json({"type": "error", "code": "PROMPT_INJECTION_BLOCKED", "message": exc.message})
                    continue
                words = result.reply.split(" ")
                for i in range(0, len(words), 6):
                    await ws.send_json({"type": "token", "text": " ".join(words[i:i + 6]) + " "})
                await ws.send_json({"type": "done", "data": {"reply": result.reply, "provider": result.provider,
                                                              "model": result.model, "iterations": result.iterations,
                                                              "session_id": session_id}})
        except WebSocketDisconnect:
            pass
        finally:
            _release(ip)

    @app.websocket("/realtime/voice")
    async def ws_voice(ws: WebSocket):
        principal, ip = await _ws_guard(ws)
        if principal is None:
            return
        agent = base_agent()
        agent.registry = PrincipalBoundTools(governed, principal)
        session = VoiceSession(agent, settings, ws.send_json, session_id=f"{principal.tenant}:voice-{uuid.uuid4().hex[:8]}")
        try:
            while True:
                incoming = await ws.receive_json()
                if incoming.get("type") == "ping":
                    await ws.send_json({"type": "pong"})
                    continue
                await session.handle(incoming)
        except WebSocketDisconnect:
            pass
        finally:
            _release(ip)

    @app.websocket("/realtime/vision")
    async def ws_vision(ws: WebSocket):
        principal, ip = await _ws_guard(ws)
        if principal is None:
            return
        try:
            while True:
                incoming = await ws.receive_json()
                if incoming.get("type") == "ping":
                    await ws.send_json({"type": "pong"})
                    continue
                image_b64 = incoming.get("data_b64") or incoming.get("image_b64") or ""
                source = str(incoming.get("source", "stream"))[:64]
                try:
                    data = base64.b64decode(image_b64)
                except Exception:
                    await ws.send_json({"type": "error", "message": "invalid base64 frame"})
                    continue
                analysis = analyze_image_bytes(data, source=source, tesseract_cmd=settings.tesseract_cmd)
                await ws.send_json({"type": "vision.analysis", "data": analysis.model_dump()})
        except WebSocketDisconnect:
            pass
        finally:
            _release(ip)

    @app.on_event("shutdown")
    async def shutdown():
        # Stop the background queue worker; the SQLite-backed singleton stores are
        # process-lived and intentionally left open (closing them here breaks the
        # multi-client / reload scenarios that reuse the same app instance — the OS
        # reclaims the handles at process exit).
        try:
            queue.stop()
        except Exception:
            pass

    return app


app = create_app()
