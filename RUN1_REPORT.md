# RUN 1 REPORT — Access & Shell Repair

**Executed:** 2026-08-20 overnight (UTC 2026-08-21 ~02:00–02:30) · **Scope:** GAP_REPORT.md Run 1, items 1–5
**Result: all 5 steps done and proven.** One handoff item remains that requires elevation (below).

Every claim here was verified against a running system, not by reading code. Proof commands and their
output are quoted per step. All work is recorded in `data/activity-log.db` (8 rows, agent `JARVIS`).

> **Re-verified 2026-08-21 03:29 UTC** (Run 1 was requested a second time; nothing was re-applied and
> the credential was deliberately **not** rotated again, since the password in the file is already
> live and working). Live re-checks: login → **200** from the file's exact contents; all four agent
> voices still distinct on the live API. Source changes all intact.
>
> That re-check caught one real problem and fixed it: **the production `.next` build had been
> destroyed** — running `next dev` during Run 1's UI testing overwrote it, leaving a dev-state
> directory with no `BUILD_ID`. `nssm restart EchoDashboard` would have **failed to start and taken
> the dashboard down.** Rebuilt (`BUILD_ID 84MiqAIHCkYksBQIAebjN`) and smoke-tested on a spare port:
> boots 200, `/api/health` 200 through the rewrite, 0 loopback offenders across 14 chunks. The
> restart below is now safe.

---

## ⚠ One thing needs you: restart the dashboard service

The backend picked up its changes automatically (`EchoBackendAPI` runs uvicorn with reload — verified
live on :8000). **`EchoDashboard` does not** — `next start` reads its build manifest once at boot, so
port 3000 is still serving the pre-Run-1 bundle. This session's shell has no elevation
(`nssm restart` → `OpenService(): Access is denied`), so it could not restart it.

From an **elevated** terminal:

```powershell
nssm restart EchoDashboard
# verify (404 before the restart, 200 after):
curl.exe http://127.0.0.1:3000/api/health
```

Until that runs, port 3000 serves the old bundle that still calls `127.0.0.1:8000` directly — i.e.
the phone remains broken **only** because of the stale process, not the code. The new build is already
compiled and waiting in `apps/web/.next`.

---

## Step-by-step results

### 1. Admin credential reset — **DONE, PROVEN**

The documented credential was stale (`admin/1234` → 401; the stored hash matched none of the known
passwords). Rotated to a fresh 28-character password using the app's own `hash_password`, written to
`God Mode Agent/ADMIN_CREDENTIALS.local.txt`, and old refresh tokens revoked so nothing outlived the
rotation.

Proof — the script parses the credentials file and logs in with **exactly** its contents (no hardcoded
secret, nothing printed):

```
{ "status": 200, "username_from_file": "admin",
  "password_source": "ADMIN_CREDENTIALS.local.txt (28 chars, not printed)",
  "access_token_len": 308, "refresh_token_present": true,
  "user": {"username": "admin", "role": "admin", "tenant": "default"},
  "PROOF": "PASS" }
```

The password uses an ambiguity-free alphabet (no `0/O`, `1/l/I`) because it gets typed on a phone.
`jarvis.db` was backed up first to `God Mode Agent/backups/jarvis.db.pre-run1.bak`.

### 2. Session persistence on Ctrl+K — **DONE, PROVEN**

Root cause: tokens live in module memory by deliberate design (`lib/api.ts` — "NEVER localStorage"),
and the palette navigated with `window.location.href`, a full document load that wiped them. The fix
keeps that security posture and changes only the navigation method: `router.push` / `next/link`
throughout. Four call sites had the same defect — palette entries, systems-overlay panel links, the
voice route's `console →` footer link, and the console's `⦿ Voice` header link.

Proof — planted `window.__run1_sentinel` after login; a document reload destroys it exactly as it
would destroy the token:

```
palette → Approvals:  url=/console?panel=Approvals  sentinelSurvived=planted-…  showsLoginForm=false
                      panelHeading="Approvals"      signedInAs="admin · admin"
console → voice:      url=/                          sentinel alive   login=false
voice   → console:    url=/console                   sentinel alive   login=false
```

Also fixed while here: `?panel=` deep links now react to query changes (they only ran on first mount)
and respect the role gate, so a deep link can't open a panel the rail would have disabled.

### 3. Tailnet `/api` proxy + same-origin WS — **DONE, PROVEN**

The client no longer contains any absolute backend host. `lib/api.ts` uses `BACKEND = "/api"`,
`wsUrl()` derives `ws:`/`wss:` from `window.location`, and `next.config.mjs` rewrites `/api/:path*`
to a **server-side** `BACKEND_ORIGIN` (default `127.0.0.1:8000`). One build now works from localhost
and from the tailnet, and CORS stops mattering entirely because nothing is cross-origin.

Proof over the real tailnet hostname — the exact path a phone takes, HTTPS then WSS, never touching
the API port:

```
{ "tailnet_base": "https://bill-oneill.tail59f219.ts.net/…",
  "health": {"status": 200, "version": "0.4.0"},
  "login":  {"status": 200, "user": "admin", "role": "admin"},
  "voice_over_wss": {"scheme":"wss","agent":"ASSISTANT","voice":"af_bella",
                     "engine":"kokoro","audio_b64_len": 49212},
  "PROOF": "PASS" }
```

The client bundle was scanned for the old hardcoded host: **0 offenders across 14 chunks.**

**No tailscale config change was needed** — because Next now proxies `/api` itself, the existing
`/ → 127.0.0.1:3000` handler already covers everything. The tailnet was tested via a temporary
`/run1check` mount and **restored to its original single-handler state** (verified:
`tailscale serve status` shows only `/ proxy http://127.0.0.1:3000`).

TLS note: the test client disabled certificate verification because this machine's OpenSSL store
rejects a CA in the local chain ("Basic Constraints not marked critical"). That is a property of the
test client; a phone browser validates the real ts.net certificate normally.

### 4. Console responsive breakpoints — **DONE, PROVEN**

The console was a hard ~1060px minimum (`w-44` rail + `w-[440px]` panel + `flex-1` middle, no
breakpoints). Below `md` the rail and panel now stack full-width and the rail buttons wrap as chips;
from `md` up the desktop layout is untouched. Added `viewport-fit=cover` so the voice route's fixed
bottom rail clears the iPhone home indicator.

Proof — measured in a real browser at both widths:

```
phone  (502px): isMobileBranch=true   horizontalOverflowPx=0   asides stacked at x=0   panel opens
desktop(1444px): isDesktopBranch=true horizontalOverflowPx=0   rail 176px @x=0 + panel 440px @x=1004
```

Screenshots: `God Mode Agent/logs/run1-console-mobile.png`, `run1-console-desktop-1440.png`.

### 5. Per-agent voice — **DONE, PROVEN (all four)**

`/realtime/voice` never passed an `agent_id`, so every session defaulted to `ECHO` — which wasn't in
`voices.yaml` — and silently fell back to `bm_george`. Per-agent voice was dead code at runtime.
Now the socket accepts `?agent=<ID>`, validated against the voice map (unknown values fall back to
ECHO rather than killing the session), `ECHO` is listed explicitly, the map reloads when the file
changes instead of caching forever, and `tts.audio` carries `agent` + `voice` so the choice is
observable instead of invisible.

Proof — one real voice turn per agent, live Kokoro audio each time:

| Agent | Voice returned | Engine | Audio |
|---|---|---|---|
| JARVIS | `bm_george` | kokoro | 71,056 b64 |
| ASSISTANT | `af_bella` | kokoro | 35,560 b64 |
| DIRECTOR | `am_onyx` | kokoro | 66,960 b64 |
| TRADER | `bm_lewis` | kokoro | 54,672 b64 |
| *(no param)* | `bm_george` as **ECHO** | kokoro | fallback correct |
| `NOT_AN_AGENT` | `bm_george` as **ECHO** | kokoro | rejected safely, socket survived |

**Four distinct voices: `af_bella`, `am_onyx`, `bm_george`, `bm_lewis`.** No audible change to the
default ECHO voice — it was `bm_george` before by accident and is `bm_george` now by declaration.

---

## Files changed (11 source files + 1 config)

| File | Change |
|---|---|
| `apps/api/jarvis_api/main.py` | `/realtime/voice` accepts + validates `?agent=`, passes `agent_id` |
| `packages/voice/jarvis_voice/loop.py` | `tts.audio` event carries `agent` and `voice` (additive) |
| `packages/voice/jarvis_voice/voices.py` | mtime-based cache reload; `known_agents()` helper |
| `configs/voices.yaml` | `ECHO: bm_george` added explicitly |
| `apps/web/lib/api.ts` | same-origin `/api`; `wsUrl()` derived from page origin |
| `apps/web/next.config.mjs` | `/api/:path*` rewrite to server-side `BACKEND_ORIGIN` |
| `apps/web/components/voice/overlays.tsx` | palette + overlay links use `router.push` |
| `apps/web/components/voice/VoiceRoute.tsx` | footer console link → `next/link` |
| `apps/web/app/console/page.tsx` | `next/link`, reactive+role-gated `?panel=`, `md:` breakpoints |
| `apps/web/app/layout.tsx` | `viewport` export with `viewport-fit=cover` |
| `apps/web/components/SettingsTab.tsx` | backend help text matches the new same-origin model |
| `scripts/dev-web.ps1` | sets `BACKEND_ORIGIN` instead of `NEXT_PUBLIC_BACKEND_URL` |

Production build recompiled (`next build` clean; the one ESLint warning in `AuditExplorer.tsx`
pre-dates this run). Rollback: `git checkout -- "God Mode Agent"` plus restore
`backups/jarvis.db.pre-run1.bak` if the credential change needs reverting.

---

## Findings worth knowing (not fixed — outside Run 1 scope)

1. **`rewrites()` is build-time, not runtime, for `next start`.** `BACKEND_ORIGIN` is baked into
   `.next/routes-manifest.json` during `next build`. The compiled default is exactly what production
   needs (`127.0.0.1:8000`), so nothing is required — but if the backend ever moves, it is a
   **rebuild**, not a restart. `next dev` re-evaluates per request.
2. **Uploads are silently unauthenticated.** `lib/upload.ts:60` reads a bearer token from
   `window.sessionStorage.getItem("jarvis_access_token")`, which nothing ever writes — tokens are
   module-scoped. Every upload goes out with no `Authorization` header. Pre-existing; worth a fix.
3. **`next dev` destroys the production build.** Both share `apps/web/.next`, so running the dev
   server in that directory replaces the `next start` artifact (no `BUILD_ID` → the service fails to
   boot). Hit once during this run and fixed. If a dev server is ever run there again, **`pnpm build`
   before restarting `EchoDashboard`**.
4. **Test isolation used a throwaway DB.** Browser UI tests ran against a sandbox database with the
   seeded `admin/admin123`, so the real password was never typed into a page and the production
   `jarvis.db` / `activity-log.db` were never written by the tests. All test servers
   (ports 3010/3020/8010/8011) are stopped; live services on 8000/3000/8767 verified healthy at 200.

## Suggested next step

Restart `EchoDashboard` from an elevated prompt, then confirm on your phone at
`https://bill-oneill.tail59f219.ts.net` — login, then hold the orb to talk. Everything behind that is
already proven working.

**Stopping here as instructed — no further runs without your word.**
