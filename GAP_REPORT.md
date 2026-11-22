# GAP REPORT — Running App vs Required Features

**Date:** 2026-08-20 · **Method:** six parallel code+live audits against the running stack
(web `127.0.0.1:3000` = `God Mode Agent/apps/web`, API `127.0.0.1:8000`, Kokoro TTS `127.0.0.1:8767`,
tailnet `https://bill-oneill.tail59f219.ts.net`). Every verdict traced UI → API → real logic → persistence.
**No credit for stubs** — mock providers, prompt-only "features", and unwired config entries score PARTIAL at best.

> **P0 found during audit:** `POST /auth/login` with the credential documented in
> `God Mode Agent/ADMIN_CREDENTIALS.local.txt` returns **401** — the stored pbkdf2 hash for the single
> `admin` user matches none of the known passwords. **Nobody can log into the dashboard today.**

---

## Verdict table

| # | Feature | Sub-feature | Verdict | Evidence |
|---|---------|-------------|---------|----------|
| 1 | Settings UI | Account settings page | **EXISTS & WORKS** | `SettingsTab.tsx:35-37` → live `/users`, `/apikeys`, register/reset/role/disable routes |
| 1 | Settings UI | Password change | **EXISTS & WORKS** | `AccountPanel.tsx:27` → `auth_routes.py:130` → `service.py:185` (verify + rehash + revoke sessions) |
| 1 | Settings UI | Voice selection per agent | **MISSING** | No UI, `/voices` → 404, no code ever writes `configs/voices.yaml`. Worse: `main.py:524` never passes `agent_id`, so runtime defaults to `ECHO` (absent from voices.yaml) → every voice turn falls back to `bm_george`. Per-agent voice is dead code. |
| 1 | Settings UI | Model selection | **MISSING** | Display-only (`SettingsTab.tsx:65`); `/models` → 404; model is env-var + restart (`config.py:41-42`) |
| 2 | AIDE (20 WBS items, not 19) | 1.1 Inbox triage | **PARTIAL** | Real schedulable operator `src/openjarvis/recipes/data/operators/inbox_triage.toml` — but no Gmail tool in its toolset and **not loaded by the live app** |
| 2 | AIDE | 1.2.1 Drafting in Bill's tone | **MISSING** | Only a `think`-prompt chain (`email-draft.toml`); Gmail connector is `gmail.readonly`; no tone corpus |
| 2 | AIDE | 1.2.2 Gatekeeping (decline/delegate drafts) | **MISSING** | Zero hits outside wishlist |
| 2 | AIDE | 1.3 Thread summarization | **PARTIAL** | Real `gmail_get_thread` tool (`src/openjarvis/connectors/gmail.py:361`) but no summarizer, no caller, not in live app |
| 2 | AIDE | 1.4 Follow-up tracking / nudges | **MISSING** | No sent-mail state, no timer, no nudge generator |
| 2 | AIDE | 2.1 Proactive scheduling / focus blocks | **MISSING** | Only single-shot `createEvent` in unwired Java module |
| 2 | AIDE | 2.2 Daily briefings | **PARTIAL** | News-only digest demos + prompt chains; no schedule/bios/open-items brief, no morning job, not in live app |
| 2 | AIDE | 2.3 Conflict & delay notifications | **MISSING** | No overlap detection anywhere |
| 2 | AIDE | 2.4.1 Transit: location extraction | **MISSING** | `gcalendar.py` reads fields; no geocoding/GPS |
| 2 | AIDE | 2.4.2 Transit: live traffic (Google Maps) | **MISSING** | Zero hits for any Maps/directions/traffic API repo-wide |
| 2 | AIDE | 2.4.3 Transit: departure-time calc | **MISSING** | No ETA computation |
| 2 | AIDE | 2.4.4 Transit: 3-tier alerts (30/15/leave-now) | **MISSING** | No alert tiers, no travel notifier |
| 2 | AIDE | 3.1 Minutes capture | **PARTIAL** | Real STT exists (`jarvis_voice/stt.py`); pipeline is a prompt chain over a hand-supplied transcript file; no ingest/store/UI |
| 2 | AIDE | 3.2 Action items → ECHO task log | **MISSING** | **No task log exists**: no task table, no task API, no task UI (`/crew/tasks` is agent delegation, not a personal task list) |
| 2 | AIDE | 3.3 MoM distribution | **MISSING** | `email_send` only in unwired Java module; nothing calls it |
| 2 | AIDE | 3.4.1 Deliverable reminders | **PARTIAL** | Java `reminder_set` stores a string; nothing ever fires; not in live app |
| 2 | AIDE | 3.4.2 Completion reporting in brief | **MISSING** | No completion state, no brief to report into |
| 2 | AIDE | 4.1 Document & deck production | **MISSING** | No pptx/docx/template code |
| 2 | AIDE | 4.2 Mobile voice-to-task | **MISSING** | Voice chat loop exists, but no memo capture → task/email extraction, no task sink |
| 2 | AIDE | 4.3 Personal & travel logistics | **MISSING** | No travel/itinerary/booking code |
| 3 | PRECON | Go/No-Go integration | **PARTIAL** | Scorecard exists only as prompt knowledge (`configs/knowledge/BIDS.md`) injected into the crew loop (`crew.py:120`); no model, persistence, endpoint, or UI |
| 3 | PRECON | SOW pipeline access | **MISSING** | Zero SOW code; `/rag/ingest` is manual text paste only |
| 3 | PRECON | 26020 tracking | **MISSING** | "26020" appears only in the wishlist files |
| 3 | PRECON | GovTribe / SAM.gov hooks | **MISSING** | Zero `govtribe` matches in code; only external-data tool is a **hardcoded mock** (`web_search.py:16` returns "Mock result N") |
| 4 | DESK | Pre-market brief | **MISSING** | No market-data client, scheduler job, or endpoint |
| 4 | DESK | Phaser journal | **MISSING** | `phaser` appears only in wishlist; no dependency, no journal table |
| 4 | DESK | Session recap | **MISSING** | No trade/session store; TRADER agent is defined in configs but **hard-rejected** by the crew (`crew.py:91-92`) and unreachable from chat |
| 5 | Memory | Persistent user memory | **EXISTS & WORKS** | `jarvis.db:memory_items` (full CRUD, TTL/decay, conflicts) + Chroma vector store with a live embedding |
| 5 | Memory | Viewable in UI | **PARTIAL** | Governed memory listable (`MemoryGovTab.tsx` → `/memory/items`); vector memory is search-only — the store that actually holds data cannot be browsed |
| 5 | Memory | Editable in UI | **PARTIAL** | Add/pin/forget/export wired; backend `/memory/items/{id}/edit` and `/memory/conflicts` are live but **no UI calls them**; vector memory has no delete path at all |
| 6 | Approvals queue | Generic tool-approval flow | **EXISTS & WORKS** | `approvals` table → `GET/POST /approvals*` → `ApprovalQueue.tsx` tab; approval actually gates + executes held tool calls (proven by integration test) |
| 6 | Approvals queue | Bill sign-off on emails/external docs | **MISSING** | **No email/document send path exists to gate** (5-tool registry, zero gmail/smtp hits). Also 3 holes: admins bypass high-risk gating (`engine.py:139`), workflow steps call the **ungoverned** registry (`main.py:169`), and no self-approval check (`governance_routes.py:79-94`) |
| 7 | Integrations panel | Panel UI | **PARTIAL** | `IntegrationsPanel.tsx` renders `/integrations/catalog` — a self-described "catalog scaffold — not yet wired"; provider status = env-var presence, never probed |
| 7 | Integrations panel | Gmail / Calendar / Drive / GovTribe | **MISSING** | None wired; Drive is a hardcoded "planned" string; OAuth provider is a **mock** (`oauth.py:37` fabricates profiles). NB: real read-only Gmail/GCal/GDrive connectors exist in the **disconnected** `src/openjarvis` codebase |
| 8 | Command palette (Ctrl+K) | | **PARTIAL — functional but broken in practice** | Real handler + real navigation to `/console?panel=…` — but destinations-only (zero executable commands), and `window.location.href` full-reload **wipes the in-memory token → every palette jump lands on the login screen** (`overlays.tsx:205`, `lib/api.ts:7`) |
| 8 | "S" systems overlay | | **EXISTS & WORKS** | Live `/system/health` (real Kokoro probe + real `tailscale serve status` shell-out) + activity feed, 15s repoll |
| 9 | Console | Agent detail views | **MISSING** | `AgentCards.tsx` has no onClick/href; no per-agent history endpoint exists; `GET /agents/sessions` has zero frontend callers |
| 9 | Console | Log search | **MISSING** | `/activity/recent` accepts only `limit`; no WHERE/LIKE in `ActivityLog`; no search UI (audit-chain category filter is a separate PARTIAL) |
| 9 | Console | Cost tracking | **PARTIAL** | Real ledger → real UI, but tokens are `len(text)//4`, prices from a static table with blind fallback, latency hardcoded 0, **no per-agent/per-run grain**, and WS chat/voice paths record nothing |
| 10 | Mobile / tailnet | | **PARTIAL — shell loads, app is dead remotely** | Tailscale serves only `:3000`; every client fetch/WS is baked to `http://127.0.0.1:8000` (`lib/api.ts:2`), so a phone talks to itself → login fails, voice shows "link down" forever. CORS allows only `127.0.0.1:3000`. `/console` is a fixed ~1060px layout with zero breakpoints. Voice route itself is genuinely fluid + touch-ready; HTTPS secure context is fine. |

### Scorecard

| Verdict | Count |
|---|---|
| EXISTS & WORKS | 6 |
| PARTIAL | 13 |
| MISSING | 26 |

**Structural finding:** the repo contains three codebases. The live app (`God Mode Agent`) has solid
infrastructure (auth, governance, memory, crew loop, voice) but almost none of the domain features.
The real Gmail/Calendar/Drive connectors, scheduler, and email send live in `src/openjarvis` and `java/`
— **neither is imported by the running app**. The fastest path to AIDE is wiring those assets in, not rewriting them.

---

## Build order — overnight-sized runs (proposal only, nothing built)

Ordering logic: restore access first, then the platform pieces everything else depends on
(Google connectors + gated send + scheduler + task log), then AIDE features stacked on that platform,
then the PRECON/DESK verticals, then console depth.

### Run 1 — Access & shell repair (unblocks everything; small but critical)
1. Fix admin credentials (reset hash, rewrite `ADMIN_CREDENTIALS.local.txt`) — **P0, nobody can log in**.
2. Session persistence across navigation (sessionStorage/cookie rehydration) — fixes the palette/console-link logout bug.
3. Tailnet: `tailscale serve --set-path /api → 127.0.0.1:8000` (+ Kokoro if needed), switch web to same-origin base URL via `next.config.mjs` rewrites (kills the hardcoded `127.0.0.1` and the CORS problem in one move); verify WS upgrade.
4. `/console` responsive pass: `md:`-gate the `w-44` rail and `w-[440px]` panel into drawers; add `viewport-fit=cover`.
5. Voice fix: pass real `agent_id` at `main.py:524`, add `ECHO` to `voices.yaml`, drop the forever-cache.

### Run 2 — Settings completion + memory UI
1. Model selection: `GET/PUT /settings/model` (persisted, hot-swap in router) + UI picker per agent and global.
2. Voice per agent: `GET /voices` (proxy Kokoro catalog) + `PUT /agents/{id}/voice` with a persisted store (DB, not YAML rewrite) + Settings UI.
3. Memory: wire Edit button to existing `/memory/items/{id}/edit`; surface `/memory/conflicts`; add vector-store browse/list + delete endpoint and UI.

### Run 3 — Google platform + gated send (the AIDE foundation)
1. Port `src/openjarvis` Gmail/Calendar/Drive connectors into `God Mode Agent` as governed tools; real OAuth (replace mock `oauth.py` exchange) with per-service token storage.
2. Add `email_draft`/`email_send` + `calendar_create` tools, registered as HIGH_RISK.
3. Close the approval holes: remove admin bypass for send-class tools, route workflow steps through `governed.execute`, add self-approval check.
4. Integrations panel: live `is_connected()` health checks per service (pattern already exists in `openjarvis/server/connectors_router.py`).
**Result: any agent email/doc leaving the machine lands in Bill's approval queue — feature 6 done for real.**

### Run 4 — Task log + scheduler (second AIDE foundation)
1. Personal task/reminder tables + CRUD API + console tab (the "ECHO task log" that 3.2/3.4/4.2 need).
2. Port the `openjarvis` operator scheduler (or a cron worker) into the live app so recurring jobs can fire.
3. Notification dispatcher (dashboard toast + TTS announce; hook for push later).

### Run 5 — AIDE inbox (WBS 1.1–1.4)
1. Inbox triage: scheduled Gmail poll → Urgent/Review/FYI/Awaiting-Response classification → console inbox panel.
2. Thread summarization (3-bullet + callouts) on demand.
3. Response drafting in Bill's tone (seed a tone profile from sent mail) + gatekeeping decline/delegate drafts — all drafts → approvals queue.
4. Sent-mail follow-up tracking with auto-nudge drafts.

### Run 6 — AIDE calendar & briefs (WBS 2.1–2.3, 3.4.2)
1. Morning daily brief: schedule + participants + open items + task/deliverable status → dashboard + email (gated) + TTS.
2. Conflict & delay detection with notifications.
3. Proactive scheduling / focus-block protection (draft responses to invites, gated).

### Run 7 — AIDE meetings & voice-to-task (WBS 3.1–3.3, 4.2)
1. Minutes pipeline: transcript ingest (upload or STT) → summary + decision log stored + attendee MoM distribution (gated).
2. Action-item extraction → task log with owners.
3. Voice-to-task: memo capture on the voice route → task/email extraction → task log/drafts.

### Run 8 — AIDE transit alerts (WBS 2.4.1–2.4.4)
1. Location extraction from invites + geocoding; Google Maps traffic; target departure calc; 30/15/leave-now alert tiers through the Run 4 notifier. (Isolated vertical; needs a Maps API key.)

### Run 9 — PRECON vertical
1. `opportunities`/`bids` tables + API + console tab; seed the 26020 record.
2. Go/No-Go scorecard as persisted structured data (crew BIDS worker fills it, Bill decides in UI).
3. GovTribe + SAM.gov clients replacing the mock `web_search` path; SOW ingest → RAG pipeline.

### Run 10 — DESK vertical
1. Market data source + scheduled pre-market brief.
2. Trading journal (incl. the Phaser surface) + session recap; un-quarantine TRADER into its own isolated loop (keep the construction/personal data wall).

### Run 11 — Console depth + long tail
1. Agent detail drill-in (per-agent history endpoint + UI), wire `GET /agents/sessions`.
2. Log search: WHERE/LIKE + agent/status/date filters on `activity_log` + search UI.
3. Cost v2: real token counts from provider usage fields, per-agent/per-run grain, record WS chat/voice, real price table.
4. Deck/memo production (4.1) and travel logistics (4.3) — lowest priority.

**Dependencies:** Runs 1–2 are independent quick wins. Run 3 blocks 5–7; Run 4 blocks 5–8. Runs 9/10/11 are independent of each other and can be reordered by business priority — recommend PRECON (Run 9) ahead of DESK given active bid work.
