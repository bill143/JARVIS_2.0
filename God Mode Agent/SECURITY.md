# JARVIS God Mode Agent — Security

Phase 2 hardening reference: threat model, secure configuration, incident
response, and key rotation. See also [README.md](README.md) §Phase 2 and
[RUNBOOK.md](RUNBOOK.md).

## Threat model (high level)

| Asset | Threat | Mitigation |
|---|---|---|
| Model/tool access | Unauthenticated use | JWT auth on every non-public endpoint; WS auth handshake (`?token=`) |
| Privileged actions | Privilege escalation | RBAC (`readonly < user < operator < admin`); high-risk tools require operator role or approval |
| Tool execution | Dangerous/abusive calls | Central policy engine evaluates **every** call (role, tenant, args risk, domains, paths) → allow/deny/require_approval/redact_and_allow |
| Python sandbox | Code escape / resource abuse | Isolated subprocess, restricted builtins, import whitelist (blocks os/subprocess/socket), CPU+memory rlimits (POSIX), hard wall-clock kill (Windows) |
| Prompt path | Prompt injection / data exfiltration | Heuristic injection classifier with risk scoring; untrusted content (user, OCR, web) fenced/compartmentalized; blocked above threshold |
| Secrets/PII | Leakage via logs/responses | Secret masking on all logs + error envelopes + audit details; PII detection/redaction; `redact_and_allow` policy action |
| Tenant data | Cross-tenant access | Memory/session/audit namespaced by tenant; operators scoped to their tenant |
| Auth endpoints | Brute force | Per-account lockout + stricter per-IP rate limit on `/auth/login` |
| Audit trail | Tampering | SHA-256 hash-chained audit log; `/audit/verify` detects the first broken link |
| Providers | Outage / cascading failure | Retry with jitter, per-provider circuit breakers, deterministic mock fallback |
| Requests | Oversized / cross-origin | Body size cap, strict CORS allowlist, security headers |

**Trust boundaries:** tool output and any external/OCR/web text are *untrusted
data* — never merged into privileged instructions. The model system prompt
explicitly refuses instructions found inside fenced `UNTRUSTED_CONTENT`.

## Secure configuration checklist

- [ ] `ALLOW_DEV_AUTH_BYPASS=false` (default) in any shared/production environment.
- [ ] Set strong random `JWT_SECRET` and `JWT_REFRESH_SECRET` (32+ bytes). Blank = ephemeral per-process dev secret (all tokens invalid on restart).
- [ ] Change the seeded `admin/admin123` password immediately (register a new admin, disable/rotate the default).
- [ ] `POLICY_DEFAULT_ACTION=deny` (default) — only known low/medium-risk tools for authorized users pass.
- [ ] `CORS_ALLOWED_ORIGINS` set to your exact web origin(s), never `*` in production.
- [ ] `POLICY_DOMAIN_ALLOWLIST` restricted to domains web tools may reach.
- [ ] `ENABLE_AUDIT_LOG_HASH_CHAIN=true`.
- [ ] `RATE_LIMIT_PER_MIN` tuned; auth endpoints keep the stricter limiter.
- [ ] Secrets provided via environment/secret manager, never committed. `.env` is git-ignored.
- [ ] TLS terminated in front of the API (HSTS header is already emitted).
- [ ] `MAX_REQUEST_BODY_MB` sized to your needs.

## Incident response basics

1. **Contain** — set `ALLOW_DEV_AUTH_BYPASS=false`; if a token secret is suspected leaked, rotate `JWT_SECRET`/`JWT_REFRESH_SECRET` (invalidates all sessions). Revoke suspect API keys (mark `revoked`).
2. **Assess** — query the audit log: `GET /audit?category=auth|policy|tool` (admin sees all tenants). Run `GET /audit/verify` to confirm the trail is intact.
3. **Eradicate** — add `deny` policy rules for abused tools/subjects via the Policy Console; disable compromised users.
4. **Recover** — restore from backup if needed (see RUNBOOK), re-run migrations, re-enable services.
5. **Review** — the hash-chained audit log + structured JSON logs (correlated by `request_id`) are the forensic record.

## Key rotation guidance

- **JWT signing keys**: replace `JWT_SECRET` / `JWT_REFRESH_SECRET` and restart. All existing access + refresh tokens become invalid (users re-login). Rotate on a schedule and immediately on suspected compromise.
- **API keys** (`jk_…`): keys are stored only as SHA-256 hashes; the raw key is shown once at creation. To rotate, mint a new key (`POST /apikeys`), update the client, then revoke the old one.
- **Provider keys** (OpenAI/Anthropic/Deepgram/ElevenLabs): rotate at the provider, update `.env`, restart. Fallbacks keep the system available during rotation.
- **Seeded admin**: create a replacement admin and stop using `admin/admin123`.

## Reporting

Security issues: contact the maintainers privately. Do not open a public issue for an unpatched vulnerability.
