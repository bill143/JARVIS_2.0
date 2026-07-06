# TRACEABILITY

| Requirement ID | Source Repo | Capability | Target Module/Package | Files | Status |
|---|---|---|---|---|---|
| REQ-STEP-001 | N/A (spec structural requirement) | Java multi-module scaffold (Maven, 9 modules, Java 21) | `java/` root + all module roots | `pom.xml`, `*/pom.xml`, module source/test directories | COMPLETED |
| REQ-STEP-002 | `paperclipai/paperclip` | Memory pattern (primary): scoped store/key/retrieve of agent/task context — Java reimplementation of the pattern only, no Paperclip domain models | `memory / com.jarvis.memory` | `memory/src/main/java/com/jarvis/memory/{MemoryStore,MemoryEntry,InMemoryStore}.java`, `memory/src/test/java/com/jarvis/memory/InMemoryStoreTest.java` | COMPLETED |
| REQ-STEP-003 | Derived from spec capability categories | Tool execution: Tool contract + call/result types + thread-safe registry/dispatcher | `tool-execution / com.jarvis.tools` | `tool-execution/src/main/java/com/jarvis/tools/{Tool,ToolCall,ToolResult,ToolRegistry}.java`, `tool-execution/src/test/java/com/jarvis/tools/ToolRegistryTest.java` | COMPLETED |
| REQ-STEP-004 | `isair/jarvis` | Agent control loop: bounded iterate–decide–act–observe driver with pluggable policy seam | `core-agent / com.jarvis.agent.loop` | `core-agent/src/main/java/com/jarvis/agent/loop/{AgentLoop,AgentPolicy,Decision,AgentContext,AgentStep,AgentResult}.java`, `core-agent/src/test/java/com/jarvis/agent/loop/AgentLoopTest.java` | COMPLETED |
| REQ-STEP-005 | `NousResearch/hermes-agent` | Prompt routing: ordered first-match prompt→capability selection with pluggable matcher seam | `core-agent / com.jarvis.agent.routing` | `core-agent/src/main/java/com/jarvis/agent/routing/{PromptRouter,Route,RouteMatcher}.java`, `core-agent/src/test/java/com/jarvis/agent/routing/PromptRouterTest.java` | COMPLETED |
| REQ-STEP-006 | `NousResearch/hermes-agent` | Task planning: goal→ordered sub-task decomposition model with tracked step lifecycle and pluggable planner seam | `planning / com.jarvis.planning` | `planning/src/main/java/com/jarvis/planning/{Planner,Plan,PlanStep,StepStatus}.java`, `planning/src/test/java/com/jarvis/planning/PlanTest.java` | COMPLETED |
| REQ-STEP-007 | Derived from spec capability categories | Retrieval pattern (adapter-only RAG): read/write adapter contracts + document/query/result value types, no engine | `rag / com.jarvis.rag` | `rag/src/main/java/com/jarvis/rag/{Retriever,DocumentIndexer,Document,RetrievalQuery,ScoredDocument}.java`, `rag/src/test/java/com/jarvis/rag/RetrievalContractTest.java` | COMPLETED |
| REQ-STEP-008 | `open-jarvis/OpenJarvis` (upstream authoritative) | Orchestration: end-to-end request coordination (route → loop → tools → memory) + plan-driven runs | `core-agent / com.jarvis.agent.orchestration` | `core-agent/src/main/java/com/jarvis/agent/orchestration/{Orchestrator,PlanRun}.java`, `core-agent/src/test/java/com/jarvis/agent/orchestration/OrchestratorTest.java` | COMPLETED |
| REQ-STEP-009 | `open-jarvis/OpenJarvis` (upstream authoritative) | Voice interaction pattern: wake-gated audio→transcribe→handle→synthesize turn pipeline over STT/TTS adapter contracts | `speech / com.jarvis.speech` | `speech/src/main/java/com/jarvis/speech/{VoicePipeline,SpeechToText,TextToSpeech,PromptHandler,AudioClip,Transcription,VoiceTurn}.java`, `speech/src/test/java/com/jarvis/speech/VoicePipelineTest.java` | COMPLETED |
| REQ-STEP-010 | `paperclipai/paperclip` | Plugin/tool adapter pattern (secondary): self-describing plugins contributing tools through validated instance-wide registration | `integrations / com.jarvis.integrations` | `integrations/src/main/java/com/jarvis/integrations/{Plugin,PluginDescriptor,PluginManager}.java`, `integrations/src/test/java/com/jarvis/integrations/PluginManagerTest.java` | COMPLETED |
| REQ-STEP-011 | N/A (spec public interface requirement) | API surface: transport-free programmatic facade (`JarvisApi`) over the orchestrator with public request/response types | `api / com.jarvis.api` | `api/src/main/java/com/jarvis/api/{JarvisApi,DefaultJarvisApi,ChatRequest,ChatResponse,PlanRequest,PlanResponse}.java`, `api/src/test/java/com/jarvis/api/DefaultJarvisApiTest.java` | COMPLETED |
| REQ-STEP-012 | N/A (spec UI inspiration only) | UI inspiration placeholder: rendering seam (`UiRenderer`) + message type + trivial text renderer | `ui / com.jarvis.ui` | `ui/src/main/java/com/jarvis/ui/{UiRenderer,UiMessage,PlainTextRenderer}.java`, `ui/src/test/java/com/jarvis/ui/PlainTextRendererTest.java` | COMPLETED |

## Notes
- Dependency whitelist in effect: `com.fasterxml.jackson:jackson-databind`, `org.junit.jupiter:junit-jupiter` only.
- `open-jarvis/OpenJarvis` is authoritative upstream source for orchestration/voice extraction.
- `isair/jarvis` contributes **agent control loop only**; no orchestration extraction.
- `rag` source is treated as derived from spec capability categories with adapter interfaces only.

## REQ-STEP-002 notes
- Source boundary honored: only the memory *pattern* (how agent/task context is stored, keyed, retrieved, scoped) was reimplemented. Paperclip's org-chart, company, goal, budget, and governance domain models were **not** ported. The pattern was separable from those models, so no clarification was required.
- Public contract is `MemoryStore<V>`; `InMemoryStore<V>` is the concrete implementation. Other modules must depend on the interface, never on `InMemoryStore` directly.
- Thread-safety via `java.util.concurrent` (`ConcurrentHashMap`); no external caching libraries and no new dependencies (Jackson remains in `dependencyManagement`, unused).
- **Persistence:** none in this step (in-memory only, no durability). Durable storage was intentionally deferred per the Step 2 constraint; if a durable backend is later required, it should be introduced as an alternate `MemoryStore` implementation behind the existing interface.

## REQ-STEP-003 notes
- Source is derived from spec capability categories (per approved correction) — no upstream repository code was ported.
- Public contract is the `Tool` interface plus `ToolCall`/`ToolResult` records; `ToolRegistry` dispatches by name. Other modules depend on `Tool`/`ToolRegistry`, never on concrete tool implementations.
- Registration is first-wins: duplicate names fail fast (`IllegalArgumentException`). Dispatch never throws for tool-level problems — unknown names and exceptions thrown by tools are returned as failed `ToolResult`s.
- Thread-safety via `java.util.concurrent` (`ConcurrentHashMap`); no new dependencies (whitelist unchanged, Jackson still unused).
- No concrete production tools in this step — only a test fake inside the test suite. Real tools arrive with their owning capabilities in later steps.

## REQ-STEP-004 notes
- Source boundary honored: `isair/jarvis` (Python) runs a continuous listen → interpret intent → select tool → execute → synthesize → respond loop. Only the **control loop mechanism** (bounded iterate–decide–act–observe with observations fed back into the next decision) was reimplemented. Speech capture, intent classification, tool routing, planning, and memory recall were **not** extracted — those belong to REQ-STEP-005/006/008/009 and plug in through the `AgentPolicy` seam.
- Public contract is `AgentPolicy` + `Decision` (sealed: `Respond` | `Invoke`) + the immutable `AgentContext`/`AgentStep`/`AgentResult` records; `AgentLoop` is the concrete driver.
- Loop semantics: tool failures are observations, not terminations; `maxSteps` bounds tool executions, and an exhausted budget still allows one final tool-free response before stopping with `MAX_STEPS_REACHED`.
- **Internal module dependency introduced:** `core-agent → tool-execution` (`com.jarvis:tool-execution:${project.version}`) so decisions dispatch through the Step 3 `ToolRegistry`/`ToolCall`/`ToolResult` contract instead of duplicating those types. This is an intra-project dependency; the external dependency whitelist is unchanged (Jackson still unused).

## REQ-STEP-005 notes
- Source boundary honored: `hermes-agent` (Python) dispatches prompts implicitly by matching user intent to available skills/toolsets. Only the **selection mechanism** was reimplemented: ordered candidate routes, first match wins, selection separated from execution. Skill files, toolset management, and autonomous skill creation were **not** ported.
- Public contract is `RouteMatcher` (the matching seam — keyword/regex now, embedding or LLM intent classification can plug in later without touching the router) plus `Route<T>`/`PromptRouter<T>`. Targets are generic: routing selects, callers execute.
- `PromptRouter` is immutable (thread-safe by construction), enforces unique route names, and offers `routeOrDefault` for explicit fallback handling. No new dependencies of any kind.

## REQ-STEP-006 notes
- Source boundary honored: `hermes-agent` (Python) decomposes goals into delegated sub-tasks tracked through agent state. Only the **planning model** was reimplemented: a goal decomposed into an ordered list of sub-tasks with a tracked lifecycle (`PENDING → IN_PROGRESS → COMPLETED|FAILED`) and orderly progression (`nextPending`, `isComplete`, `hasFailure`). Subagent spawning, parallel delegation, trajectory generation, and skill learning were **not** ported.
- Public contract is `Planner` (the decomposition seam — LLM planning passes or heuristics plug in later) plus the immutable `Plan`/`PlanStep`/`StepStatus` model. Progress is tracked by immutable derivation (`withStepStatus`), the same idiom as the agent loop's context evolution.
- Plan step ids are unique (fail-fast), unknown-id updates fail fast, and the module has zero dependencies beyond JUnit (test scope). External whitelist unchanged.

## REQ-STEP-007 notes
- Adapter-only boundary honored (per approved correction): the module ships **contracts and value types only** — `Retriever` (read side), `DocumentIndexer` (write side, separated so read-only consumers never see indexing), and immutable `Document`/`RetrievalQuery`/`ScoredDocument`. No retrieval engine, no embedding model, no vector store.
- Score semantics are adapter-defined; the contract requires only descending order within a result list and at most `topK` results. The keyword-scoring fake lives inside the test suite purely to prove the contracts are implementable together — it is not production code.
- Zero dependencies beyond test-scope JUnit. External whitelist unchanged (Jackson still unused).

## REQ-STEP-008 notes
- Source boundary honored: `open-jarvis/OpenJarvis` (Python core) orchestrates input → agent selection → skill discovery/invocation → response, with an on-demand Orchestrator agent at the center. Only that **coordination mechanism** was reimplemented, wired from this project's existing seams: `PromptRouter` selects the `AgentPolicy` (agent selection), `AgentLoop` + `ToolRegistry` execute (skill invocation), `MemoryStore` records each exchange under the session's scope, and `Planner` decomposes goals for plan-driven runs. LLM execution, speech, scheduling (Morning Digest), continuous agents, GUI, and the skill catalog were **not** ported.
- Plan-driven semantics: each pending step is routed and run like a normal prompt; `RESPONDED → COMPLETED`, budget exhaustion → `FAILED`; execution continues past failures so the returned `PlanRun` reflects every step's outcome; `succeeded()` = complete with no failures.
- Session recording: keys `turn-<n>` in the session-id scope, value `prompt -> response` (or `<no response>`), metadata carries `stopReason` and `toolSteps`.
- **Internal module dependencies added:** `core-agent → memory` and `core-agent → planning` (flagged in advance in the Step 7 report). No dependency cycles (memory and planning depend on nothing internal); external whitelist unchanged (Jackson still unused).

## REQ-STEP-009 notes
- Source boundary honored: `open-jarvis/OpenJarvis` documents TTS-driven spoken output (e.g. the spoken morning digest) in a local-first design; its README does not spell out the full voice turn flow, so the extraction is the canonical voice pattern that design implies — wake-gated audio → transcribe → handle → synthesize — reduced to contracts. No speech engine, audio I/O, or wake-word model was ported or implemented (the whitelist forbids audio libraries anyway).
- Public contracts are `SpeechToText`/`TextToSpeech` (engine adapters), `PromptHandler` (the seam the orchestrator plugs into — speech never depends on agent internals; wiring direction is decided at composition time, e.g. in the API layer), and the immutable `AudioClip`/`Transcription`/`VoiceTurn` value types. `VoicePipeline` is the concrete turn driver.
- Wake-word semantics: case-insensitive containment; non-matching utterances are ignored (`Optional.empty()`); the wake word and everything before it are stripped from the handler prompt while the transcription preserves the full utterance. `AudioClip` defensively copies bytes both ways and is value-comparable.
- Zero dependencies beyond test-scope JUnit. External whitelist unchanged.

## REQ-STEP-010 notes
- Source boundary honored: `paperclipai/paperclip` (TypeScript) runs an instance-wide plugin system whose relevant capability here is **"tool exposure to agents"** — plugins register once and extend the platform without core modifications. Only that registration/contribution mechanism was reimplemented. Out-of-process workers, capability-gated host services, job scheduling, and UI contributions were **not** ported.
- Public contracts are `Plugin` (descriptor + contributed tools) and `PluginDescriptor`; `PluginManager` performs validated installation into the shared Step 3 `ToolRegistry`. Validation is all-or-nothing: duplicate plugin name, duplicate tool within a plugin, or collision with an installed tool rejects the whole plugin with no partial contribution.
- **Internal module dependency added:** `integrations → tool-execution`, so plugins contribute through the existing `Tool` contract instead of a parallel one. No cycles; external whitelist unchanged (Jackson still unused).

## REQ-STEP-011 notes
- Spec-derived (no source repository): the public programmatic surface of the platform. `JarvisApi` is the one interface external callers depend on; everything behind it (routing, loop, tools, memory, planning) stays an implementation detail.
- **Transport-free by design:** the dependency whitelist admits no web framework, so no HTTP/websocket binding was implemented. A transport layer would wrap `JarvisApi` in a later phase without changing it.
- `DefaultJarvisApi` is a thin mapping layer over the Step 8 `Orchestrator` — no logic of its own. Response types follow the platform's "value XOR not-completed" invariant idiom (`ChatResponse`, `PlanResponse.StepOutcome`).
- **Internal module dependency added:** `api → core-agent` (transitively tool-execution, memory, planning). No cycles; external whitelist unchanged (Jackson still unused).

## REQ-STEP-012 notes
- Placeholder by mandate (spec UI inspiration only): the module ships the minimal rendering seam a future UI mounts onto — `UiRenderer` + `UiMessage` — and `PlainTextRenderer` writing `role> text` lines to any `Appendable`, purely to prove the seam renders.
- Deliberately zero dependencies (not even internal): nothing in the platform depends on `ui`, and `ui` depends on nothing, so any real UI (console, desktop, web) can replace this wholesale. External whitelist unchanged (Jackson still unused).

## Phase 7 (post-spec, user-approved)
| Requirement ID | Source Repo | Capability | Target Module/Package | Files | Status |
|---|---|---|---|---|---|
| REQ-STEP-013 | N/A (build tooling) | Maven Wrapper so local builds need no Maven install | `java/` root | `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` | COMPLETED |
| REQ-STEP-014 | N/A (Anthropic Messages API, spec LLM seam) | Claude-backed `AgentPolicy` (first real reasoning engine) | `integrations / com.jarvis.integrations.llm` | `AnthropicPolicy.java`, `AnthropicPolicyTest.java` | COMPLETED |
| REQ-STEP-015 | N/A (runnable entry point) | Console launcher + executable fat jar | `app / com.jarvis.app` | `Main.java`, `AppWiring.java`, `AppWiringTest.java`, `app/pom.xml` | COMPLETED |
| REQ-STEP-016 | N/A (user-requested dashboard) | Browser dashboard over JDK built-in HTTP server (`GET /`, `GET /status`, `POST /chat`) | `app / com.jarvis.app` | `WebServer.java`, `resources/dashboard.html`, `WebServerTest.java`, `Main.java` (web default, `--console` fallback) | COMPLETED |
| REQ-STEP-017 | `FatihMakes/Mark-XLVIII` (ideas only — see notes) | Assistant tools (clock, system info, reminders, open URL) as a plugin + tool-aware LLM policy | `integrations / com.jarvis.integrations.mark` + `.llm` | `MarkToolsPlugin.java`, `MarkToolsPluginTest.java`, `AnthropicPolicy.java` (tool protocol), `AnthropicPolicyTest.java` | COMPLETED |
| REQ-STEP-018 | `FatihMakes/Mark-XLVIII` changelog (ideas only) | Mark-parity where applicable: parallel first-wins news search, exponential-backoff API retry, "sir" persona, one-click startup briefing | `integrations` + `app` | `NewsTool.java`, `NewsToolTest.java`, `AnthropicPolicy.java` (persona + `withRetry`), `dashboard.html` (BRIEFING), `AppWiring.java` (budget 6) | COMPLETED |

## REQ-STEP-014/015 notes
- **Jackson is now in use** — the first (and only) consumer of the whitelisted `jackson-databind`, in `integrations` for Anthropic request/response JSON. Transport is the JDK's built-in `java.net.http.HttpClient`; the external dependency whitelist is still just Jackson + JUnit.
- `AnthropicPolicy` sits behind the existing `AgentPolicy` seam; a transport interface makes it fully testable offline. API failures become graceful `Respond` messages, never loop-crashing exceptions. Model defaults to `claude-sonnet-5`, overridable via `JARVIS_MODEL`; key via `ANTHROPIC_API_KEY`.
- **Internal deps added:** `integrations → core-agent` (for the policy seam) and the new `app` module → `{api, integrations, ui}`. Still no cycles.
- `app` is the composition root: router (empty, fallback-only) + Claude-or-offline-echo policy + trivial single-step planner + `InMemoryStore` + `ToolRegistry`, exposed as `JarvisApi`, driven by a stdin/stdout console loop rendered through the ui module. Without a key the app runs in offline echo mode, so the pipeline is exercisable before any credentials exist.
- Packaging: `maven-shade-plugin` (build plugin, not a dependency) produces the self-contained executable `app/target/jarvis.jar` (`Main-Class: com.jarvis.app.Main`).

## REQ-STEP-016/017 notes
- **Dashboard (016):** built on the JDK's `com.sun.net.httpserver` — the no-web-framework rule holds; the whitelist is still Jackson + JUnit. `dashboard.html` is a single self-contained page (inline CSS/JS, no CDN). Default port 8080, `JARVIS_PORT` overrides; `Main` opens the browser when a desktop is available and always prints the URL; `--console` preserves the terminal mode.
- **License boundary (017):** `FatihMakes/Mark-XLVIII` is Python under CC BY-NC 4.0 (non-commercial). **No code was read from or copied out of that repository** — only its publicly described capability catalog (clock/system control/reminders/opening things) informed which tools to build. Every line is an original Java implementation on this platform's own `Tool` contract, keeping JARVIS_2.0 unencumbered.
- **Tool-aware LLM (017):** `AnthropicPolicy` optionally takes the `ToolRegistry`; the system prompt advertises the catalog and a one-line protocol (`TOOL: <name> <json-args>`). Tool replies become `Decision.Invoke`; prior observations are replayed into the next request via the loop's `AgentContext`, so multi-step tool use works without the native tool-use API. Malformed tool lines degrade to plain responses. Backed by a fake-transport round-trip test through the real `AgentLoop`.
- `reminder` tools persist through the Step 2 `MemoryStore` (scope `reminders`), shared with the orchestrator's session store in `AppWiring`. New internal dep: `integrations → memory`; `app` now declares `jackson-databind` directly. Still no cycles.

## REQ-STEP-018 notes (Mark-XLVIII changelog parity — where it makes sense)
- Same license boundary as 017: ideas from the publicly posted changelog only; no code read or copied; all-original Java.
- **Parallel news, first result wins:** `NewsTool` races Google News RSS and Bing News RSS via `ExecutorService.invokeAny` — first valid feed wins, the loser is discarded, both failing is a graceful tool error. RSS gives real article titles/sources/links (the ddgs.news() point). Stdlib JAXP parsing with doctype disabled; fetcher seam keeps tests offline.
- **Exponential backoff:** `AnthropicPolicy.withRetry` retries transient `IOException`s at 1s → 2s → 4s before surfacing the failure (which still degrades to a polite reply, never a crash). Sleeper seam keeps tests instant.
- **Persona:** system prompt now addresses the user as "sir" and forbids mixed-language replies (the language-aware address point, English-only here).
- **Startup briefing:** dashboard BRIEFING button sends a canned prompt that chains clock + reminder_list + news_search; loop budget raised 4 → 6 to accommodate it. The upstream two-phase audio overlap is voice-specific and deferred with voice.
- **Not ported (voice/vision-specific):** instant interrupt, vision acknowledgment/cooldown/echo guard. **Already true by design:** session state isolation (immutable contexts, no cross-session flags). **Not applicable:** zero terminal windows (no subprocess spawning).

## Build-complete summary (Steps 1–12)
- All 12 requirements COMPLETED. Module dependency graph (all one-directional, no cycles): `core-agent → {tool-execution, memory, planning}`, `integrations → tool-execution`, `api → core-agent`; `rag`, `speech`, `ui` are dependency-free.
- External dependency whitelist was never expanded: JUnit 5 (test scope) is the only dependency in use; Jackson remains available in `dependencyManagement` and unused.
