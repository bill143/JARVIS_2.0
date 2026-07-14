# Phase 1 — Presence/Greeting + Research (JARVIS_2.0)

All feature code lives in `bill143/jarvis_2.0`. OpenHuman is referenced only as an arm's-length
API/adapter contract (RFC 0001) — **no OpenHuman code is imported or modified**, and these Phase-1
capabilities (presence, greeting, research) don't call OpenHuman at all.

## Capabilities
- **Identity confidence policy** (`presence` module) — a person is greeted **by name only** when
  **opt-in consent** is given **and** identity confidence ≥ threshold (default 0.75) **and** private
  mode is off. Otherwise a **neutral** greeting; no person → no greeting.
- **Proactive greeting orchestrator** — context guards (**quiet hours / meeting / DnD**) suppress any
  greeting before identity is even considered.
- **Research pipeline** (`research` module) — plan → search → (fetch) → dedupe → **synthesize with
  `[n]` citations**. Sources are preserved structurally, so every run is traceable even if the model
  omits inline markers.

## Safety defaults (enforced)
- **Feature-flagged OFF by default:** `/presence/greet` and `/research` return **503** unless
  `JARVIS_PRESENCE_ENABLED=true` / `JARVIS_RESEARCH_ENABLED=true`.
- **Opt-in identity only** — no personalization without explicit consent.
- **Local-first** — the policy layer takes only the *outcome* (name/confidence), never raw imagery;
  identity storage stays in `~/.jarvis/people.json`.
- **Full audit** — every proactive greeting (greeted *or* suppressed) and every research run is
  written to the audit log; the greeting audit records type + reason, **not** the person's name.
- **Deny-by-default durable writes** — provided by the OpenHuman write path (RFC 0001 / PR #2), a
  separate change; this PR introduces **no new durable-write surface**.

## Enable + run (Windows)
```cmd
setx JARVIS_PRESENCE_ENABLED true
setx JARVIS_RESEARCH_ENABLED true
:: reopen terminal, then rebuild + run
cd /d C:\Users\BillAsmar\JARVIS_2.0\java
.\mvnw.cmd -q clean package -DskipTests
java -jar app\target\jarvis.jar
```

## Endpoints
- `POST /presence/greet` — body `{present, name?, confidence, consent, privateMode, quietHours,
  inMeeting, doNotDisturb}` → `{greeted, type, text, reason}`. (503 when disabled.)
- `POST /research` — body `{question}` → `{question, answer, sources:[{index,title,url}]}`.
  (503 when disabled.)

## Tests
- `presence`: `IdentityConfidencePolicyTest` (6), `GreetingOrchestratorTest` (6).
- `research`: `ResearchPipelineTest` (5) — citation preservation, dedupe/cap, graceful empty, fetch
  resilience.
- `app` (`WebServerTest`): personalized-on-opt-in+confidence, neutral-on-low-confidence,
  guard-suppression, **audited**, and **503-when-disabled** for both endpoints.
