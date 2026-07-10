# J.A.R.V.I.S. — Master Build Plan (v0.2 → sellable desktop product)

> Approved plan. Baseline = `SPEC.md` (Steps 1–33). This roadmap covers Steps 34+.
> Rules unchanged: Java 21, Maven, JDK `HttpServer` dashboard, whitelist = Jackson + JUnit 5,
> JDK‑only HTTP/OS, native installer (not a web app). Foundation‑first: security & governance
> are built before any feature sits on top of them.

## Architectural decisions (locked)
| # | Decision | Choice |
|---|---|---|
| D1 | Storage backend | **Flat‑file + thin storage abstraction** now; swap to SQLite later only if real scale demands it. |
| D2 | Multi‑provider LLM seam | **Extract `LlmProvider` interface now**, Claude as the only implementation. Adding OpenAI/Gemini/Groq later = a new class, not surgery. Not user‑visible. |
| D3 | Embeddings / semantic memory | **Plan for cloud embeddings at Phase 4** (Voyage/OpenAI‑embeddings — an embeddings key is not a chat provider). Don't skip; don't build yet. |
| D4 | Code signing | **Signed installer before public distribution** (OV/EV cert ~$200–400/yr). Phase 2 packaging designed to expect a signed artifact. |
| — | Licensing model | **Offline signed‑key** for v1 (no server). *Online activation must be added later for seat limits / revocation.* |
| — | Installer | **jpackage → .msi** primary; **Launch4j `.exe`** fallback if WiX blocks the build machine. |

## Module map — reuse vs. new
**Existing (reused / extended):** `core-agent` (routing→chat modes, orchestration→workflows/agents),
`planning` (workflows), `tool-execution` (registry metrics/health + manifests + risk tiers),
`memory` (storage abstraction; optional vector index), `rag` (lit up for Knowledge Base + semantic
memory), `speech` (as‑is), `integrations` (`LlmProvider` seam, new tool plugins), `api` (new
endpoints), `ui` (as‑is), `app` (multi‑page shell, new pages/endpoints).

**New modules (v0.2):** `audit-log`, `plugin-registry` (manifest + risk tiers), `licensing`
(offline signed‑key), `updater` (notify‑only, signed manifest), `usage-metering` (provider‑agnostic),
`security` (permission‑prompt policy engine).

**New feature areas:** `tasks` (Kanban), `workflows` (+ scheduler), `knowledge-base`, multi‑page shell.

## Phased build sequence
- **Phase 0 — Seams (small):** D1 storage abstraction; D2 `LlmProvider` extraction (Claude‑only). No user‑visible change. *~2 steps.*
- **Phase 1 — Security & governance foundation (first, non‑negotiable):** `audit-log` + Audit Log page; `plugin-registry` manifests + risk tiers wired into `ToolRegistry` + tool health/status; `security` permission prompts before mutating/destructive actions; user‑visible error/status feed sourced from the audit log. *~5–6 steps.*
- **Phase 2 — Productization:** packaging (jpackage/.msi, signed; Launch4j fallback); `updater` (notify‑only); `licensing` (offline signed‑key, encrypted store, graceful locked state — online activation flagged for later); `usage-metering` (HUD widget + page); onboarding wizard. *~6–7 steps. Needs D4 before public release.*
- **Phase 3 — Command Center UI shell + surfacing existing data:** left‑nav multi‑page shell; migrate HUD into Command Center home; Memory, Conversations (5 modes via routing), Tools & Skills (registry health), System Monitor, AI Core pages. *~6–8 steps.*
- **Phase 4 — New capability features:** Tasks (Kanban + deps); Workflows (steps + schedule/webhook/manual triggers + run history); Knowledge Base (+ rag); multi‑agent executions; Personal Intelligence / semantic memory (needs D3). *~8–12 steps.*
- **Phase 5 — External integrations:** manifest‑based plugins (GitHub, Hubstaff, Jira, WhatsApp, MIHCM, …). Small on the JARVIS side thanks to Phase 1 plumbing; **the real time cost is each vendor's developer portal + auth flow (calendar friction, not code friction).** *~1–2 code steps each, ongoing.*

## Honest cost/effort flags
- Rough total ~30–40 focused tested steps across Phases 0–4; Phase 5 open‑ended per integration.
- **Money:** code‑signing cert (Phase 2); embeddings API (cents‑scale, Phase 4); each integration's own subscription/API. WiX is free.
- **Real limits:** client‑side licensing is not unbreakable (signed keys stop forgery, not determined patching; true revocation needs online activation); auto‑*applying* updates on Windows is hard (v1 = notify + download); semantic memory needs an embeddings source; MSI needs WiX on the build machine (hence Launch4j fallback); multi‑provider is a seam here, not a feature.
