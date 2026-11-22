# J.A.R.V.I.S. Java Backend — Architecture Summary

> **Scope note:** this repository (`bill143/JARVIS_2.0`) contains **two independent, unrelated
> codebases**: a Python "upstream OpenJarvis" stack (`src/openjarvis/`, `desktop/`, `frontend/`) and
> the **clean-room Java reimplementation** documented here (`java/`). They share no code, no config
> format, and no runtime. This document describes the Java side only. See `java/TRACEABILITY.md` for
> the full, step-by-step build history this summary is distilled from.

---

## 1. Core Platform & Stack

| Layer | Choice |
|---|---|
| Language / JDK | **Java 21** (`maven.compiler.release=21`), records, sealed types, virtual threads used throughout |
| Build tool | **Maven**, multi-module reactor (`java/pom.xml` is the parent `pom` aggregator); **Maven Wrapper** (`java/mvnw`, `mvnw.cmd`) so no local Maven install is required |
| Web server | **No framework.** The embedded JDK `com.sun.net.httpserver.HttpServer` (`WebServer.java`) serves both the REST API and the dashboard. This is a deliberate, enforced constraint (see dependency whitelist below), not an oversight |
| Frontend | A single self-contained `dashboard.html` (embedded resource, inline CSS/JS, no build step, no CDN, no framework) |
| Storage / "database" | **No external database.** Two custom flat-file abstractions live in the `memory` module and back everything: a keyed `MemoryStore<V>` and an append-only `RecordStore`. Durable implementations write plain files under `~/.jarvis/` (see §2) |
| Packaging | `maven-shade-plugin` produces a single executable `app/target/jarvis.jar` (`Main-Class: com.jarvis.app.Main`); `jpackage`/`jlink` produce a native Windows `.msi` with a bundled private JRE (`java/packaging/`) |
| Dependency whitelist (enforced convention) | **`com.fasterxml.jackson:jackson-databind` (2.18.2) + `org.junit.jupiter:junit-jupiter` (5.11.4, test-only) — full stop.** One approved exception: `org.apache.pdfbox:pdfbox` (3.0.3), scoped to the `documents` module only, for PDF text extraction. No web framework, no ORM/DB driver, no DI container, no logging framework, no HTTP client library (the JDK's own `java.net.http.HttpClient` is used everywhere), no audio/speech library. This whitelist has held since the very first commit and is treated as load-bearing — do not add a dependency without an explicit, documented exception like PDFBox's |

---

## 2. Project Architecture & Package Structure

### 2.1 Module map

23 library modules + the `app` composition-root module, one Maven module per `<module>` entry in `java/pom.xml`. Dependencies are one-directional; there are no cycles.

| Module | Package | Responsibility |
|---|---|---|
| `core-agent` | `com.jarvis.agent.loop` / `.routing` / `.orchestration` | The agent control loop (bounded iterate→decide→act→observe), prompt routing, and the `Orchestrator` that ties routing + loop + tools + memory + planning together for a single chat turn |
| `planning` | `com.jarvis.planning` | Goal → ordered sub-task decomposition (`Planner`, `Plan`, `PlanStep`, `StepStatus`) |
| `tool-execution` | `com.jarvis.tools` | The `Tool`/`ToolCall`/`ToolResult` contract, `ToolRegistry` dispatcher, shared `RiskTier` enum |
| `memory` | `com.jarvis.memory` | Storage abstractions: `MemoryStore<V>` (keyed; `InMemoryStore`, `FileBackedStore`) and `RecordStore` (append-only log; `InMemoryRecordStore`, `FileRecordStore`) |
| `rag` | `com.jarvis.rag` | Retrieval contracts (`Retriever`, `DocumentIndexer`, `Document`, `ScoredDocument`) plus `KeywordIndex`, `EmbeddingProvider` seam, `VectorIndex`, `SemanticMemory` |
| `speech` | `com.jarvis.speech` | STT/TTS adapter contracts + `VoicePipeline` (unused in production — the shipped voice feature is browser-native Web Speech API in `dashboard.html`; this module is a dormant seam for a future native engine) |
| `integrations` | `com.jarvis.integrations.*` | Plugin contract (`Plugin`, `PluginDescriptor`, `PluginManager`), the LLM provider seam (`.llm`: `LlmProvider`, `AnthropicProvider`, `OpenAiCompatibleProvider`, `AnthropicPolicy`), the OpenHuman adapter + Tier-2 routing engine (`.openhuman`, `.openhuman.routing`), GitHub/Google plugins, assistant tools (`.mark`: clock/system-control/news/weather/web-search/file/hardware) |
| `plugin-registry` | `com.jarvis.registry` | `tool.json` manifest loading, per-tool `RiskTier`, tool health tracking (`HealthTrackingTool`) |
| `audit-log` | `com.jarvis.audit` | Append-only structured audit trail (`AuditEvent`/`AuditEntry`/`AuditLog`), the `AuditedTool` decorator |
| `security` | `com.jarvis.security` | Permission policy + broker for gating mutating/destructive tool calls (`PermissionPolicy`, `PermissionBroker`, `AuthorizingTool`) |
| `updater` | `com.jarvis.updater` | Signed-manifest startup update check (dormant unless `JARVIS_UPDATE_URL` is set) |
| `licensing` | `com.jarvis.licensing` | Offline signed-key license verification + AES-256/GCM encrypted local license store |
| `usage-metering` | `com.jarvis.metering` | Provider-agnostic token/cost tracking (`UsageMeter`, `PriceTable`) |
| `tasks` | `com.jarvis.tasks` | Kanban-style task board (`Task`, `TaskBoard`) |
| `workflows` | `com.jarvis.workflows` | Multi-step workflow engine with MANUAL/SCHEDULE/WEBHOOK triggers, run history |
| `knowledge-base` | `com.jarvis.kb` | Durable document store (`KnowledgeBase`) built on the `rag` keyword index |
| `multi-agent` | `com.jarvis.agents` | Planner→executor→critic multi-turn agent runs (`MultiAgentManager`, `Role`) |
| `autonomous` | `com.jarvis.autonomous` | AutoGPT-style goal→next-step self-feeding loop (`AutonomousRunner`) |
| `discussion` | `com.jarvis.discussion` | Bounded chair↔advisor discussion loop (`DiscussionRunner`) — used for the JARVIS/OpenHuman "Project Discussion" feature |
| `solicitations` | `com.jarvis.solicitations` | Government-contract solicitation search/tracking adapters (SAM.gov, GovTribe, Drive/OneDrive document connectors) |
| `documents` | `com.jarvis.documents` | File text extraction: plain text/CSV/JSON native, OOXML (docx/xlsx) via JDK zip APIs, **PDF via Apache PDFBox** (the one whitelist exception) |
| `api` | `com.jarvis.api` | Transport-free public facade (`JarvisApi`, `ChatRequest/Response`, `PlanRequest/Response`) — the one interface external callers depend on |
| `ui` | `com.jarvis.ui` | Minimal rendering seam (`UiRenderer`, `UiMessage`) — deliberately a placeholder; the real UI is `dashboard.html`, not this module |
| `app` | `com.jarvis.app` | **Composition root.** Wires every module together, hosts the HTTP server, the dashboard resource, and ~30 app-level services that don't belong in a reusable library module (see §2.2) |

### 2.2 The `app` module (where almost everything actually lives)

`app` is intentionally the fattest module — it's where cross-cutting composition happens, not a library. Key classes, grouped by concern:

- **Composition & entry**: `Main.java`, `AppWiring.java` (see §3)
- **HTTP layer**: `WebServer.java` (single file, ~2000 lines, every route)
- **Model orchestration**: `OrchestrationService.java` (ensemble/hierarchy multi-model runs + Tier-2 OpenHuman routing/failover — see §4.4), `ProviderSettingsService.java` (configured providers, Conductor/Orchestrator/Worker role assignment), `RoutingSettings.java`, `GatedLaneService.java` (default-deny self-hosted-model lane), `OpenHumanWriteGate.java` (default-deny gate for OpenHuman memory writes), `SwappablePolicy.java`, `BrainManager.java`
- **Knowledge / memory**: `SemanticMemoryService.java` (the unified fact store — embeddings when live, keyword fallback when dormant), `KnowledgeGrounding.java` (per-question retrieval, scores the store against the current question and returns only the top few), `KnowledgeGraph.java` (galaxy-view node/edge builder), `BrainVault.java` (read-only Obsidian vault mirror), `VaultWatcher.java` (background poller that keeps the vault mirrored into `SemanticMemoryService` live), `RecallCapture.java`, `ConnectorSettingsService.java` (in-app config for all external connectors)
- **Agents**: `MultiAgentService.java`, `AutonomousService.java`, `AgentTeamService.java`, `AgentRegistryService.java` (persistent "standing agents" registry), `DiscussionService.java`
- **Domain services**: `SolicitationsService.java` + `SolicitationsPlugin.java`, `WorkflowService.java`, `UploadedDocsService.java`, `McpService.java` (Model Context Protocol client bridge), `HardwareMonitor.java`, `PeopleStore.java` + `PeopleRecognizer.java`, `HttpEmbeddingProvider.java`, `GoogleDriveConnector.java` / `OneDriveConnector.java`, `GoogleConnect.java`

---

## 3. Entry Points & Wiring

### 3.1 `Main.java` — the process entry point

Three modes, chosen by CLI arg:
1. **`--connect-google`** — one-shot OAuth loopback flow (`GoogleConnect.run(...)`), then exits.
2. **`--console`** — a stdin/stdout chat loop over `JarvisApi`, no HTTP server.
3. **Default** — builds the full runtime via `AppWiring.build(apiKey, model)`, starts `WebServer` on port `JARVIS_PORT` (default `8080`), tries to open the system browser, then blocks on `Thread.currentThread().join()`.

### 3.2 `AppWiring.java` — the composition root

The single place every service gets constructed and wired together. Two entry points:
- **`build(apiKey, model) → Runtime`** — the full production wiring: durable `MemoryStore` at `~/.jarvis/memory.tsv`, every plugin/tool, the audit log, permission broker, provider registry, semantic memory + Brain vault + `VaultWatcher` auto-sync, `OrchestrationService` (with Tier-2 routing + shared grounding wired in), `GatedLaneService`, and ~15 more services, bundled into a `Runtime` record.
- **`buildApi(apiKey, model, memory) → JarvisApi`** — a lighter path used by tests: no monitor, no vision, an injectable `MemoryStore`.

`Runtime` exposes a `governance()` accessor that projects a subset of fields into a `Governance` record — this is what `WebServer` actually depends on (audit log, plugin registry, permissions, providers, orchestration, etc.), keeping the web layer decoupled from wiring details it doesn't need.

Composition order matters in a few places by design — e.g. the OpenHuman client is built *after* the audit log exists so its write-gate can audit decisions; `BrainVault` is built before `OrchestrationService` so the latter can be handed the same `SemanticMemoryService` instance the vault auto-syncs into.

### 3.3 Primary HTTP endpoints (`WebServer.java`, ~65 routes)

All hand-built on `HttpServer.createContext(...)` — no router library. Grouped by area:

| Area | Representative routes |
|---|---|
| Core chat | `GET /`, `GET /status`, `POST /chat` |
| Governance | `GET /audit`, `GET /tools`, `GET/POST /permissions/*` |
| Model orchestration | `POST /orchestrate` (ensemble/hierarchy), `GET/POST /providers*`, `GET /routing/status`, `POST /routing/test`, `GET/POST /gatedlane*` |
| Knowledge / memory | `GET/POST /memory`, `GET/POST /semantic*`, `GET /knowledge/graph`, `GET/POST /kb*` |
| Brain (Obsidian) | `GET/POST /brain*` (search/cite/note/connect/writes) |
| Connectors | `GET /connectors/status`, `GET/POST /connectors/config` |
| Agents | `POST /agents/run`, `POST /agents/team/*`, `POST /autonomous/run`, `GET/POST /agents/standing`, `POST /discussion/run` |
| Productization | `GET /license*`, `GET /update`, `GET /usage` |
| Integrations | `GET/POST /mail`, `GET/POST /calendar`, `POST /mcp*`, `GET/POST /solicitations*`, `POST /upload`, `GET /uploads` |
| Misc / UX | `GET /telemetry`, `GET/POST /instructions`, `GET/POST /people*`, `POST /recognize`, `GET/POST /aboutme`, `GET/POST /onboarding*`, `GET/POST /config`, `POST /vision`, `GET/POST /tasks`, `GET/POST /workflows*` |

Every route follows the same shape: check method → parse the JSON body with Jackson → call a service → build a Jackson `ObjectNode`/`ArrayNode` → `respond(exchange, status, contentType, bytes)`.

---

## 4. Key Design Patterns & Conventions

### 4.1 Dependency injection — plain constructor injection, no container

There is no Spring/Guice/CDI. Every service takes its collaborators as constructor parameters (services, `AuditLog`, `MemoryStore`, functional-interface factories). `AppWiring` is the one place that knows how to assemble the graph. This makes every service directly `new`-able in tests with hand-built or in-memory collaborators — the dominant test pattern across the whole codebase.

**Functional-interface seams** are used pervasively to keep production code testable offline: `Function<ProviderSettingsService.Active, LlmProvider> providerFactory`, `RouteSelector.RouteExecutor`, `OpenHumanTransport`, `EmbeddingProvider`, `ModelFetcher`. Tests substitute fakes; production wires the real (`OrchestrationService::build`, `HttpOpenHumanTransport`, etc.) implementation.

### 4.2 Concurrency — virtual threads, no thread pools to manage

Fan-out work (ensemble model calls, hierarchy workers, per-call timeout enforcement) uses `Executors.newVirtualThreadPerTaskExecutor()` in a try-with-resources block, one executor per logical operation — never a shared/long-lived pool. `ConcurrentHashMap` is the default for any shared mutable state (breaker registries, pending-write queues, MCP tool bridges).

### 4.3 State management — flat files, two storage shapes, nothing else

- **`MemoryStore<V>`** (keyed, scoped get/put/query/delete) — for anything you look up by key: preferences, provider config, connector settings, conversation history. `FileBackedStore` persists to `~/.jarvis/memory.tsv` (URL-encoded TSV, atomic rewrite on mutation).
- **`RecordStore`** (append-only, ordered) — for anything that's a log: the audit trail, usage events, task/workflow run history, discussions. `FileRecordStore` appends one JSON line per record to a flat file under `~/.jarvis/<collection>`.

Both have `InMemory*` variants used everywhere in tests. There is no schema migration story because there is no schema — every persisted shape is a Jackson `ObjectNode` read back defensively (missing fields default rather than throw).

### 4.4 The routing/grounding pattern (most recently added subsystem)

`OrchestrationService.callOne()` is the single point every model call passes through (ensemble members, hierarchy decompose/work/arbitrate). Two things are layered on there, both **off by default and additive** (verified never to change behavior when disabled):
- **Tier-2 failover**: when `JARVIS_OPENHUMAN_ENABLED=true`, a `RouteSelector` (per-target circuit breaker: Closed→Open→Half-Open) tries the Tier-1 primary first, failing over to OpenHuman on timeout/429/5xx/malformed response.
- **Shared grounding**: every call is scored against the unified semantic store (`KnowledgeGrounding.retrieve(...)`, same mechanism the main chat's `/chat` handler uses) and the top few relevant notes are prepended to that specific call's prompt — so Conductor/Orchestrator/Worker/OpenHuman all draw on the same persistent knowledge (including the Obsidian vault, auto-mirrored in by `VaultWatcher`) instead of starting cold each call.

### 4.5 Governance — audit + permission gating as decorators

Tools are wrapped in layered decorators at registration time (outermost first): `AuthorizingTool` (blocks on a permission prompt for mutating/destructive risk tiers) → `AuditedTool` (records every invocation, arguments-only, never values) → `HealthTrackingTool` (feeds circuit-breaker-style health status) → the raw `Tool`. A denied action is never audited as executed and never counts against health.

### 4.6 Testing

- **JUnit 5** (`5.11.4`), Maven Surefire, one test class per production class in a mirrored `src/test/java` tree per module.
- No mocking framework in the whitelist — "mocks" are hand-written fakes implementing the same functional-interface seams described in §4.1 (fake `LlmProvider`, fake `OpenHumanTransport`, fake `RouteExecutor`, etc.).
- **Convention established during recent feature work**: any non-trivial change is verified with the target test class run **3 consecutive times** (catches flaky/racy tests before they land), followed by a **full reactor regression** (`./mvnw test` from `java/`, no module filter) with `JARVIS_OPENHUMAN_ENABLED=false` explicitly set, to pin down that new optional subsystems are byte-for-byte inert when their flag is off. As of the most recent change, the full reactor is **611 tests across 103 test classes, 0 failures**.
- Build the whole reactor offline once cached: `./mvnw -o install -DskipTests` (from `java/`), then `./mvnw -o test`.

---

## 5. Environment & External Dependencies

### 5.1 Environment variables

| Variable | Used by | Purpose |
|---|---|---|
| `ANTHROPIC_API_KEY` | `Main` | Enables the default Claude-backed brain; without it the app runs in offline "echo" mode |
| `JARVIS_MODEL` | `Main` | Overrides the default model id (`claude-sonnet-5`) |
| `JARVIS_PORT` | `Main` | Dashboard HTTP port (default `8080`) |
| `JARVIS_GITHUB_TOKEN` | `ConnectorSettingsService` / GitHub plugin | Enables GitHub tools |
| `JARVIS_OPENHUMAN_URL`, `OPENHUMAN_CORE_TOKEN` (alt: `JARVIS_OPENHUMAN_TOKEN`) | `ConnectorSettingsService` / OpenHuman client | Connects to a running `openhuman-core serve` instance |
| `JARVIS_OPENHUMAN_ENABLED` | `RoutingSettings` | Master switch for Tier-2 routing/failover (default `false`) |
| `JARVIS_ROUTING_FAILOVER_ENABLED`, `JARVIS_ROUTING_TIMEOUT_MS`, `JARVIS_ROUTING_MAX_RETRIES`, `JARVIS_ROUTING_BREAKER_FAIL_THRESHOLD`, `JARVIS_ROUTING_BREAKER_WINDOW_SEC`, `JARVIS_ROUTING_BREAKER_COOLDOWN_SEC` | `RoutingSettings` | Tier-2 circuit-breaker/timeout tuning (all optional, safe defaults) |
| `OBSIDIAN_VAULT_PATH` | `BrainVault` | Local Obsidian vault folder to mirror read-only into the unified knowledge store |
| `JARVIS_EMBEDDINGS_KEY`, `JARVIS_EMBEDDINGS_ENDPOINT`, `JARVIS_EMBEDDINGS_MODEL` | `HttpEmbeddingProvider` | Cloud embeddings for semantic (meaning-based) recall; dormant (keyword fallback) if unset |
| `SAMGOV_API_KEY`, `SAMGOV_BASE_URL` | `SolicitationsService` | Live SAM.gov solicitation search |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | `AppWiring.googleAuth` | Google Workspace OAuth (Gmail + Calendar) |
| `GOOGLE_DRIVE_ALLOWED_FOLDER_IDS`, `ONEDRIVE_ALLOWED_FOLDER_IDS` | Drive/OneDrive connectors | Scope document-connector access to specific folders |
| `JARVIS_UPDATE_URL` | `AppWiring.updateChecker` | Hosted, signed update manifest URL; dormant (no network) if unset |

Every one of these can *also* be set in-app (Settings → Connectors), which is resolved live and takes precedence over the environment variable, with no restart required (`ConnectorSettingsService`).

### 5.2 External services / APIs

- **Anthropic Messages API** (native) — the default reasoning engine.
- **Any OpenAI-compatible endpoint** — NVIDIA, OpenRouter, Groq, Mistral, DeepSeek, xAI, or a local Ollama instance — selectable per configured provider.
- **OpenHuman core** — a separate, arm's-length local process (JSON-RPC 2.0 over HTTP, bearer auth), consulted read-only (Tier 1) and as an optional delegated/failover target (Tier 2). GPL-3.0 licensed upstream; JARVIS only ever speaks HTTP to it, no code is linked.
- **Google Workspace** — Gmail + Calendar via raw REST + OAuth 2.0 (no Google SDK).
- **Google Drive / OneDrive** — read-only document connectors.
- **SAM.gov** — federal contract solicitation search.
- **GitHub API** — repo/issue/PR tools.
- **Model Context Protocol (MCP) servers** — arbitrary user-added HTTP MCP servers, bridged into the tool registry.
- **Embeddings providers** (OpenAI/Voyage-style REST) — optional, for meaning-based recall.

All of the above are reached with the JDK's own `java.net.http.HttpClient` + Jackson for JSON — no SDK for any of them, consistent with the dependency whitelist in §1.
