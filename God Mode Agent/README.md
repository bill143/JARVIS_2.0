# JARVIS God Mode Agent

Phase 1 (MVP+) multimodal agent, hardened for production by **Phase 2** (auth,
policy, safety, reliability, observability, governance). Phase 1 docs are below;
**[jump to the Phase 2 section](#phase-2--safety-security-reliability-hardening)**
for the security/ops model. See also [SECURITY.md](SECURITY.md) and [RUNBOOK.md](RUNBOOK.md).

---

## Phase 1 (MVP+)

A Windows-first, multimodal AI agent monorepo: chat with tool use, voice (STT/TTS),
vision (webcam + OCR), persistent memory, a FastAPI backend, a Next.js web UI, and a
desktop camera/mic bridge. **Everything works offline** — every external provider
(OpenAI, Anthropic, Deepgram, ElevenLabs, Tesseract) has a deterministic mock or
local fallback, and real providers activate automatically when you add API keys.

## 1. Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Windows | 10/11 | PowerShell 7+ recommended (Windows PowerShell 5.1 also works) |
| Python | 3.11+ | on PATH |
| Node.js | 20+ LTS | on PATH |
| uv | any | optional, preferred over pip |
| pnpm | any | optional, preferred over npm |
| Tesseract-OCR | optional | for real OCR — [UB Mannheim installer](https://github.com/UB-Mannheim/tesseract/wiki); auto-detected in `C:\Program Files\Tesseract-OCR`, or set `TESSERACT_CMD` in `.env` |

## 2. Setup (PowerShell)

```powershell
cd "C:\dev\JARVIS_2.0\God Mode Agent"
.\scripts\setup.ps1
```

This verifies toolchain versions, creates `.venv`, installs Python deps (uv → pip
fallback), installs optional capability deps (best effort — Chroma, OpenCV, Pillow,
pytesseract, pyttsx3, sounddevice), installs web deps (pnpm → npm fallback), creates
`data\chroma`, `workspace`, `logs`, and copies `.env.example` → `.env`.

## 3. Run

```powershell
.\scripts\dev-api.ps1       # FastAPI backend on http://127.0.0.1:8000 (Ctrl+C stops)
.\scripts\dev-web.ps1       # Next.js UI on http://127.0.0.1:3000
.\scripts\dev-desktop.ps1   # desktop camera/mic bridge (add -Once for a single frame)
.\scripts\dev-all.ps1       # all three in separate windows (prints PIDs + stop commands)
```

Makefile equivalents exist (`make dev-api`, etc.) but the `.ps1` scripts are authoritative.

## 4. Test / Lint / Format

```powershell
.\scripts\test.ps1     # unit + integration + e2e (49 tests; passes fully offline)
.\scripts\lint.ps1     # ruff + next lint
.\scripts\format.ps1   # ruff format + import sort + eslint --fix
```

## 5. Environment variables (`.env`)

| Variable | Default | Purpose |
|---|---|---|
| `OPENAI_API_KEY` | *(empty)* | GPT-4o primary chat provider |
| `ANTHROPIC_API_KEY` | *(empty)* | Claude Sonnet fallback provider |
| `DEEPGRAM_API_KEY` | *(empty)* | primary STT (else local whisper → mock) |
| `ELEVENLABS_API_KEY` | *(empty)* | primary TTS (else pyttsx3 → mock WAV) |
| `DATABASE_URL` | `sqlite:///./jarvis.db` | SQLite for sessions, event logs, tool audit |
| `CHROMA_PERSIST_DIR` | `./data/chroma` | vector memory persistence dir |
| `DEFAULT_MODEL_PROVIDER` | `openai` | `openai` \| `anthropic` \| `mock` |
| `DEFAULT_MODEL_NAME` | `gpt-4o` | model for the primary provider |
| `ENABLE_FALLBACKS` | `true` | provider failover chain (…→ mock) |
| `SAFE_WORKSPACE_DIR` | `./workspace` | only dir file tools may touch |
| `MAX_TOOL_ITERATIONS` | `6` | agent loop guard |
| `API_HOST` / `API_PORT` | `127.0.0.1` / `8000` | backend bind address |
| `WEB_PORT` | `3000` | web dev server port |
| `DESKTOP_RUNTIME_PORT` | `8010` | reserved for the desktop bridge |
| `TESSERACT_CMD` | *(empty)* | explicit path to `tesseract.exe` |
| `BACKEND_PUBLIC_URL` | `http://127.0.0.1:8000` | URL the web UI / desktop bridge call |

With **no keys at all**, the router falls back to a deterministic mock model that
still performs real tool calls (`compute 2+2` → sandboxed Python; `search for X` →
mock web search; `webcam snapshot` → capture + analysis).

## 6. Architecture

```
apps/
  api/              FastAPI: /health /chat /tools/execute /memory/* /sessions/{id}/logs
                    /vision/analyze + WS /realtime/{chat,voice,vision}
  desktop-runtime/  camera loop -> POST /vision/analyze (mic check via sounddevice)
  web/              Next.js 14 + TS + Tailwind: Chat/Voice/Vision/Memory/Tools/Settings tabs
packages/
  agent-core/       AgentLoop: multi-step reasoning, JSON-schema tool calls, iteration guard
  model-adapters/   OpenAI -> Anthropic -> Mock router with retry + exponential backoff
  vision/           OpenCV capture (synthetic fallback), pytesseract OCR, analyzer
  voice/            Deepgram/whisper/mock STT, ElevenLabs/pyttsx3/mock TTS, barge-in loop
  memory/           rolling context buffer + compression, Chroma (or flat-file) vectors, SQLite
  tools/            registry + allowlist + validation + audit; python sandbox, web search,
                    workspace file I/O, webcam snapshot
  shared/           Settings, Pydantic schemas, JSON logging + spans, safe-path enforcement
```

All API responses use `{success: true, data} | {success: false, error: {code, message, requestId}}`.
Rate limiting is per-client-IP per minute (429 + `Retry-After`). CORS origins come from
`CORS_ORIGINS` (default `*`). Tool executions are audited to SQLite (timestamp, args
summary, status, duration).

## 7. Deploy the web app to Vercel

```powershell
cd "C:\dev\JARVIS_2.0\God Mode Agent\apps\web"
npx vercel          # or connect the repo in the Vercel dashboard (root: apps/web)
```

Set the `NEXT_PUBLIC_BACKEND_URL` environment variable in Vercel to your publicly
reachable backend URL (e.g. a tunnel like `https://xyz.ngrok.io` or a hosted API).
The build is fully static (`pnpm build` passes), so no server runtime is needed.
Note: the FastAPI backend itself stays local/self-hosted — `docker-compose up` builds
it via `infra/Dockerfile.api`.

## 8. Troubleshooting

- **Camera**: "no camera available at device index 0" — no webcam or it's in use by
  another app. The system continues with deterministic synthetic frames (by design).
  Set a different index in `CaptureService(device_index=1)` if you have multiple cams.
- **Microphone**: real mic STT needs a `DEEPGRAM_API_KEY`. Offline, use the "Simulate
  speech" input on the Voice tab (it exercises the identical pipeline). If
  `sounddevice` failed to install, the desktop bridge logs it and continues.
- **CORS**: if the deployed web UI can't reach your API, add its origin to
  `CORS_ORIGINS` in `.env` (comma-separated) and restart the API.
- **WebSockets**: use `127.0.0.1`, not `localhost`, in `BACKEND_PUBLIC_URL` — Windows
  can resolve `localhost` to IPv6 (`::1`) while uvicorn listens on IPv4, breaking WS.
- **OCR**: `ocr_engine: "fallback"` in vision results means the Tesseract binary was
  not found. Install Tesseract-OCR or set `TESSERACT_CMD=C:\path\to\tesseract.exe`.
- **pnpm "Ignored build scripts"**: already resolved via `apps/web/pnpm-workspace.yaml`
  (`allowBuilds: unrs-resolver: false`); if it reappears, run `pnpm approve-builds`.
- **Port in use**: change `API_PORT` / `WEB_PORT` in `.env`; scripts read them.

---

# Phase 2 — Safety, Security, Reliability Hardening

Phase 2 adds identity, authorization, a policy engine, prompt-injection defenses,
reliability patterns, observability, and governance — **without breaking any Phase 1
behavior**. New backend packages: `auth`, `safety`, `policy`, `reliability`,
`observability`. New apps modules: `apps/api` auth/policy/queue/observability routes.

## Architecture changes

```
packages/
  auth/           JWT access+refresh, PBKDF2 passwords, RBAC, scoped API keys, OAuth stubs
  safety/         prompt-injection classifier, input sanitizer/compartmentalization, PII/secret redaction
  policy/         central policy engine (allow/deny/require_approval/redact_and_allow), approvals, governed tools
  reliability/    retry+jitter, per-provider circuit breakers, idempotency store, durable queue (Redis→SQLite)
  observability/  Prometheus metrics, OpenTelemetry tracing, hash-chained audit log
  shared/         + migrate.py (rollback-safe SQLite migrations), redaction, correlation-ID logging
infra/migrations/ m0001..m0007 (users, refresh_tokens, api_keys, audit_chain, approvals, idempotency+queue, policy_rules)
apps/api/         auth_routes, governance_routes, deps (auth resolution + principal-bound tools), hardened main
apps/web/         + Auth/login, Policy, Approvals, Audit, Health tabs with role guards
```

Every tool call — from `/chat`, `/tools/execute`, and the realtime websockets — now
flows through the **policy engine** and is recorded in the **hash-chained audit log**.

## Setup & migrations

```powershell
.\scripts\setup.ps1      # installs deps, applies migrations, checks for Redis
.\scripts\migrate.ps1    # apply migrations any time (idempotent, rollback-safe)
.\scripts\migrate.ps1 -Command status
.\scripts\migrate.ps1 -Command down -To 0   # roll back Phase 2 tables (Phase 1 data preserved)
```

## Auth setup

- A default admin (`admin` / `admin123`) is seeded on first boot — **change it immediately** (see SECURITY.md).
- **Login:** `POST /auth/login {username,password}` → `{access_token, refresh_token, user}`.
- **Refresh (rotation):** `POST /auth/refresh {refresh_token}` → new pair; the old refresh token is revoked.
- **Machine-to-machine:** `POST /apikeys` (admin) mints a `jk_…` key shown once; send it as `X-API-Key`.
- **OAuth-ready:** `POST /auth/oauth {provider,code}` with Google/GitHub stubs (deterministic offline; wire real exchange when credentials exist).
- **Dev bypass:** `ALLOW_DEV_AUTH_BYPASS` is **false** by default and must stay off outside local dev.
- **RBAC:** `readonly < user < operator < admin`, enforced on endpoints and tool execution.
- **WebSockets:** authenticate via `?token=<access_token>` (or `?api_key=`); connections have heartbeats (`{"type":"ping"}` → `pong`) and a per-IP connection cap.

## Policy configuration examples

Policy actions: `allow`, `deny`, `require_approval`, `redact_and_allow`. Rules are
evaluated in priority order; the risk classifier also inspects argument content,
destination domains, and filesystem paths. `POLICY_DEFAULT_ACTION=deny`.

```bash
# Deny web_search for everyone
POST /policy/rules {"scope":"org","subject":"*","tool":"web_search","action":"deny","priority":1}

# Let a specific role run the sandbox without approval
POST /policy/rules {"scope":"org","subject":"operator","tool":"python_exec","action":"allow","priority":5}

# Redact secrets/PII from a tool's output before returning it
POST /policy/rules {"scope":"org","subject":"*","tool":"web_search","action":"redact_and_allow","priority":10}
```

High-risk tools (`python_exec`, `file_write`) require `operator` role or an approval.
The Python sandbox additionally enforces CPU/memory rlimits (POSIX) and blocks
`os`/`subprocess`/`socket` imports everywhere.

## Approval workflow

1. A `user` calls a high-risk tool → `202 APPROVAL_REQUIRED` with an `approval_id` (a pending row is created).
2. An `operator`/`admin` reviews `GET /approvals` (Approvals tab) and decides:
   `POST /approvals/{id}/decide {"decision":"approved"|"denied"}`.
3. On **approve**, the tool executes server-side and the result is stored on the approval.

## Observability setup (metrics / traces / logs)

- **Metrics:** `GET /metrics` (Prometheus text format) — request latency, WS connections, tool call counts/durations/failures, model token/cost estimates, queue depth and failures. Point Prometheus at it.
- **Traces:** OpenTelemetry spans for API requests, model calls, tool execution, and queue jobs. `OTEL_EXPORTER=console` (default) or `otlp` with `OTEL_ENDPOINT`. Falls back to logged spans if OTel isn't installed.
- **Structured logs:** JSON with correlation IDs (`request_id`, `session_id`, `user_id`, `tool_call_id`); secrets masked. `LOG_LEVEL` configurable.
- **Governance audit:** hash-chained, tamper-evident. Query with `GET /audit` (role-filtered); verify integrity with `GET /audit/verify` (admin). Dashboard: `GET /observability/summary`.

## Reliability

- **Retries:** exponential backoff + jitter for provider/tool calls.
- **Circuit breakers:** per provider; open after repeated failures, half-open trial, auto-recover. Visible in `/observability/summary`.
- **Idempotency:** send `Idempotency-Key` on `/tools/execute` and `/memory/upsert`; identical retries replay the stored response, conflicting payloads get `409`.
- **Durable queue:** Redis when reachable, else SQLite fallback. Long jobs (voice, vision batch) enqueue via `POST /queue/enqueue`; failures retry then dead-letter. The API drains it in-process.
- **Graceful degradation:** providers unavailable → deterministic mock; Redis down → SQLite queue; OTel/Prometheus/Chroma absent → built-in fallbacks.

## Security testing commands

```powershell
.\scripts\security-test.ps1   # injection corpus, authz, privilege escalation, tenant isolation, rate limiting
.\scripts\test.ps1            # full suite incl. security + evals (regression-gated)
```

The eval harness (`tests/evals`) enforces pass/fail thresholds (deterministic
behavior, adversarial injection ≥85%, tool-misuse/privesc, memory isolation,
reliability chaos) so regressions fail CI.

## Failure recovery & runbook

See **[RUNBOOK.md](RUNBOOK.md)** for start/stop, migration/rollback, queue recovery,
and a diagnostics table, and **[SECURITY.md](SECURITY.md)** for the threat model,
secure-config checklist, incident response, and key rotation.

## Vercel (Phase 2 web)

The web app adds Auth/Policy/Approvals/Audit/Health tabs with role-based route
guards; auth tokens live **in memory only** (never `localStorage`), so a reload
requires re-login. Build is static (`pnpm build`). Set `NEXT_PUBLIC_BACKEND_URL`
and the backend's `CORS_ALLOWED_ORIGINS` to your web origin.

---

# Phase 3 — Intelligence Scaling, Orchestration, Enterprise Ops, Compliance

Phase 3 adds an advanced planner + workflow engine, multi-agent collaboration,
hybrid RAG with citations, model routing + cost controls, memory governance,
enterprise compliance, and a continuous eval platform — preserving all Phase 1/2
behavior. Deep dives: **[ARCHITECTURE_PHASE3.md](ARCHITECTURE_PHASE3.md)**,
**[COMPLIANCE.md](COMPLIANCE.md)**, **[EVALS.md](EVALS.md)**.

## New capabilities

- **Planner / workflows**: `POST /workflows` decomposes a goal into a task DAG;
  `/start /pause /resume /abort /status` with per-step retries, checkpoints, and
  resume. Modes: `direct`, `plan-and-execute`, `reflect-and-revise`,
  `human-approval-gated` (pauses before high-risk steps).
- **Multi-agent**: `POST /agents/run` runs Coordinator + Researcher + Coder +
  Verifier + Memory Steward over bounded debate rounds with arbitration
  (critic-override / majority) and single-agent fallback; every message is
  persisted and introspectable via `/agents/sessions/{id}`.
- **Advanced RAG**: `POST /rag/ingest` (chunking, dedup, versioning, metadata),
  `POST /rag/query` and `GET /rag/citations` return hybrid (vector + BM25) reranked
  results with citations, freshness, and per-segment confidence.
- **Memory governance**: `/memory/items` (add/list), `/edit`, `/pin`, `/forget`,
  `GET /memory/export`, `GET /memory/conflicts` — confidence, provenance, TTL/decay,
  pinning, conflict detection, strict tenancy.
- **Model routing + cost**: `POST /routing/plan` explains the chosen provider/model;
  `GET /cost/usage` and `/cost/budget` report and cap spend per tenant/user; budgets
  alert at 80% and force the mock tier when exceeded; response caching (exact+semantic).
- **Compliance**: `/compliance/status|toggle|retention|validate|export|exports` —
  retention policies, mode toggles, KMS abstraction, hash-chain validation, evidence
  export (JSON/CSV).
- **Evals**: `POST /evals/run` and `GET /evals/history` — 7 suites, quality gate with
  regression tolerance, persisted history.
- **Observability 2.0**: `GET /observability/slo` (latency, tool success, cost, RAG
  hit rate) plus Phase 3 metrics on `/metrics`.

## Windows scripts (Phase 3)

```powershell
.\scripts\setup.ps1              # installs deps + applies migrations (now m0001..m0014)
.\scripts\migrate.ps1            # apply/rollback DB migrations
.\scripts\test.ps1               # unit + integration + e2e + security + evals + compliance
.\scripts\evals.ps1 -Gate        # eval quality gate (non-zero exit on regression)
.\scripts\compliance-check.ps1   # migrations + audit-chain validation + compliance suite
.\scripts\export-evidence.ps1    # export admin evidence package (JSON/CSV)
```

## Frontend (Phase 3)

New tabs (role-gated): **Planner** (workflow graph + run), **Agents** (live multi-agent
timeline), **Knowledge** (RAG ingest/query with citation cards), **Memory Gov**
(view/edit/pin/forget/export), **Cost** (budget + usage + route explanations),
**Compliance** (modes, retention, evidence export), **Evals** (run + trend history).

