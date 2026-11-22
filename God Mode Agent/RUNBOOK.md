# JARVIS God Mode Agent — Operations Runbook

Windows-first operational procedures. See [README.md](README.md) and
[SECURITY.md](SECURITY.md).

## Service start / stop

```powershell
cd "C:\dev\JARVIS_2.0\God Mode Agent"

# Start everything (API + web + desktop bridge) in separate windows
.\scripts\dev-all.ps1

# Or individually
.\scripts\dev-api.ps1        # API on http://127.0.0.1:8000 (runs the queue worker in-process)
.\scripts\dev-web.ps1        # Web UI on http://127.0.0.1:3000
.\scripts\dev-desktop.ps1    # Desktop camera/mic bridge
```

Stop: press `Ctrl+C` in each window, or `Stop-Process -Id <PID>` (PIDs are printed
by `dev-all.ps1`). Ctrl+C is handled cleanly (graceful shutdown; the queue worker
task is stopped).

**Health checks:**
- `GET /health` — status, providers, memory/queue backend, tools.
- `GET /metrics` — Prometheus exposition (request/tool/queue/model counters).
- `GET /observability/summary` (operator) — metrics snapshot, circuit breakers, queue stats, audit integrity.

## Migrations and rollback

```powershell
.\scripts\migrate.ps1                      # apply all pending migrations (idempotent)
.\scripts\migrate.ps1 -Command status      # list applied / pending
.\scripts\migrate.ps1 -Command down -To 3  # roll back to version 3 (reverts 7..4)
.\scripts\migrate.ps1 -Command down -To 0  # roll back everything (Phase 2 tables dropped)
```

- Migrations are **rollback-safe** and idempotent (all DDL uses `IF NOT EXISTS`).
- Phase 1 data (chat/tool audit, sessions, vector memory) is preserved — Phase 2
  only *adds* tables (users, refresh_tokens, api_keys, audit_chain, approvals,
  idempotency_keys, queue_jobs, policy_rules).
- **Back up first:** copy `jarvis.db`, `jarvis.db-wal`, `jarvis.db-shm`, and the
  `data/chroma/` directory before a destructive `down`.

## Queue recovery

The durable queue uses Redis when reachable, else the SQLite fallback driver
(`queue_jobs` table). The API drains it in-process (`QUEUE_AUTOSTART=true`).

```powershell
# Inspect (operator token required)
GET  /queue/stats           # depth, running, done, dead
GET  /queue/dead            # dead-letter jobs (kind, last_error, attempts)
POST /queue/process         # manually drain one job (also automatic in-process)
GET  /queue/jobs/{id}       # single job status/result
```

- **Stuck jobs**: failed jobs retry with exponential backoff up to
  `QUEUE_MAX_ATTEMPTS`, then move to the dead-letter list (status `dead`).
- **Redis outage**: restart the API; it auto-falls back to the SQLite driver and
  logs `queue.driver_fallback`. No data loss for SQLite-backed jobs.
- **Re-drive DLQ**: inspect `GET /queue/dead`, fix the root cause, and re-enqueue
  the payload via `POST /queue/enqueue`.

## Common failure diagnostics

| Symptom | Likely cause | Action |
|---|---|---|
| `401 AUTH_REQUIRED` on every call | No/expired token | Re-login (`POST /auth/login`); the web UI auto-refreshes access tokens |
| `403 POLICY_DENIED` | A policy rule or risk classification blocked the call | Check `GET /audit?category=policy`; adjust rules in the Policy Console |
| `202 APPROVAL_REQUIRED` | High-risk tool needs approval | Approve via `POST /approvals/{id}/decide` (operator+) |
| `429 RATE_LIMITED` | Rate/brute-force limit hit | Wait for `Retry-After`; tune `RATE_LIMIT_PER_MIN` |
| `database is locked` | Concurrent SQLite writers | Handled via `busy_timeout`; if persistent, reduce concurrency or move to Redis/Postgres |
| Provider errors then `mock` replies | Provider outage → circuit breaker open | Check `/observability/summary` breakers; they auto-recover (half-open) after `recovery_time` |
| Web UI can't reach API | CORS / wrong backend URL | Set `CORS_ALLOWED_ORIGINS` and `NEXT_PUBLIC_BACKEND_URL`; use `127.0.0.1` not `localhost` for WS |
| OCR returns `fallback` engine | Tesseract binary missing | Install Tesseract-OCR or set `TESSERACT_CMD` |
| Audit verify returns `ok:false` | Tamper detected on the audit chain | Treat as an incident (SECURITY.md); preserve the DB, investigate `broken_at` |

## Testing & verification

```powershell
.\scripts\test.ps1            # unit + integration + e2e + security + evals
.\scripts\security-test.ps1   # security + evals only (injection, authz, policy, isolation)
.\scripts\lint.ps1            # ruff + next lint
```

## Backups

- SQLite: back up `jarvis.db*` (include `-wal`/`-shm`).
- Vector memory: back up `data/chroma/` (or `data/chroma/local_store.json` for the flat-file fallback).
- Restore = stop services, replace files, run `.\scripts\migrate.ps1`, restart.
