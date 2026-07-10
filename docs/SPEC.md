# J.A.R.V.I.S. — Specification (v0.1, through Step 33)

> Baseline spec of the working build. New work (v0.2 productization + batch‑1/2 features)
> is layered on top; see `ROADMAP.md`.

## 1. Identity & purpose
A local‑first personal AI assistant for Windows. A standalone **Java application** that serves a
**browser dashboard**. It talks and listens, sees the screen/webcam, controls the PC, reads/sends
Gmail & Calendar, remembers the user, and follows a real conversation. British‑butler persona
("sir"), plain‑text replies.

## 2. Tech stack & core rules (do not break)
- **Java 21**, Apache Maven multi‑module, Maven Wrapper (`mvnw`).
- **AI:** Anthropic Claude (default `claude-sonnet-5`, override via `JARVIS_MODEL`), raw REST over the JDK HTTP client.
- **Dependency whitelist:** **Jackson** (JSON) + **JUnit 5** (tests) only. Everything else — HTTP, OS control, screenshots, email/calendar — uses the JDK standard library. No third‑party SDKs, no web framework.
- **Web layer:** JDK built‑in `com.sun.net.httpserver` serving one self‑contained HTML dashboard.
- **Packaging target:** native Windows desktop app via installer (not a hosted web app).
- **Discipline:** every change is build → test → commit → push. ~200 automated tests, green.

## 3. Module map (10 Maven modules)
| Module | Role |
|---|---|
| `core-agent` | Agent control loop (decide→act→observe), prompt **routing**, **orchestration** |
| `planning` | Goal → ordered sub‑task plan model with lifecycle |
| `tool-execution` | `Tool` contract + thread‑safe `ToolRegistry` dispatcher |
| `memory` | `MemoryStore` interface; in‑memory + durable file‑backed impls |
| `rag` | Retrieval adapter contracts (Retriever/Indexer) — scaffolding, no engine yet |
| `speech` | Voice‑pattern contracts (STT/TTS/wake‑word) — seam for engines |
| `integrations` | Real capabilities: LLM policy, assistant tools, system control, Google, vision |
| `api` | `JarvisApi` facade (chat + plan) over the orchestrator |
| `ui` | Rendering seam (console mode) |
| `app` | Composition root, web server, launcher, hardware monitor, people, dashboard |

## 4. The AI brain
`AnthropicPolicy` runs Claude behind the agent loop with a text tool‑use protocol
(`TOOL: name {json}`), retry with exponential backoff (1s→2s→4s), and graceful failure (never
crashes the loop). Each turn the model receives: system persona + user memory
(preferences, About‑Me, mail/calendar directions, People directory) + recent conversation history
(~24 messages) + current message + tool results. Tool budget: up to 8 steps/turn.

## 5. Memory model
Durable file store at `~/.jarvis/memory.tsv` (survives restarts). Scopes: `preferences`, `about`,
`instructions` (mail/calendar directions), `conv:<session>` (ordered transcript), `reminders`.
People/contacts in `~/.jarvis/people.json`. Google refresh token in the memory store.

## 6. Capabilities / tool inventory (~26 tools)
- **Assistant:** `clock`, `weather` (Open‑Meteo), `news_search` (parallel Google/Bing RSS, first‑wins), `web_search` (search/research/price/compare), `hardware_status`, `remember`, `reminder_set`, `reminder_list`, `open_url`, `file_read`.
- **System control (Windows):** `app_launch` (whitelisted), `volume`, `brightness`, `wifi`, `hotkey`, `power` (sleep/lock/sign‑out/restart/shutdown, cancellable), `lock_screen`.
- **Vision:** `screen_look` (screenshot → Claude vision); webcam + on‑demand face recognition vs. saved People.
- **Google (when connected):** `email_list`, `email_send`, `email_trash`, `email_archive`, `email_unsubscribe`, `calendar_list`, `calendar_create`.

## 7. Voice
Browser‑native STT/TTS. Wake word (configurable), instant ESC interrupt, echo guard, 7 languages,
British‑male voice preference, and conversation mode (wake word once, then talk freely until quiet).

## 8. Google integration
OAuth 2.0 desktop flow (`--connect-google`), refresh token stored locally, Gmail + Calendar via raw REST.

## 9. Dashboard (HUD)
Single‑page Iron‑Man HUD: reactive arc‑reactor orb, live CPU/RAM telemetry rail, subsystem lights,
command log, status bar. Header: Briefing, Mail, Calendar, Voice, Camera, People, Settings. Full
Settings drawer + dedicated People & About‑Me page.

## 10. HTTP API
`/` `/status` `/chat` `/vision` `/recognize` `/telemetry` `/alerts` `/config` `/mail` `/calendar`
`/instructions` `/people` `/people/photo` `/aboutme`.

## 11. Security & privacy
Local‑first: keys via env, tokens/photos/memory on the PC only. Unsubscribe links fetched with a
separate un‑authenticated client (token never leaks). Destructive email actions confirm first.

## 12. Known limits (honest)
Claude‑only (no multi‑provider); no semantic/vector memory (recall = recent transcript + saved
facts); turn‑based voice (not streaming); no phone dialing; no browser tab control; no continuous
background vision.
