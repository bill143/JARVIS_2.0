# TRACEABILITY

| Requirement ID | Source Repo | Capability | Target Module/Package | Files | Status |
|---|---|---|---|---|---|
| REQ-STEP-001 | N/A (spec structural requirement) | Java multi-module scaffold (Maven, 9 modules, Java 21) | `java/` root + all module roots | `pom.xml`, `*/pom.xml`, module source/test directories | COMPLETED |
| REQ-STEP-002 | `paperclipai/paperclip` | Memory pattern (primary): scoped store/key/retrieve of agent/task context — Java reimplementation of the pattern only, no Paperclip domain models | `memory / com.jarvis.memory` | `memory/src/main/java/com/jarvis/memory/{MemoryStore,MemoryEntry,InMemoryStore}.java`, `memory/src/test/java/com/jarvis/memory/InMemoryStoreTest.java` | COMPLETED |
| REQ-STEP-003 | Derived from spec capability categories | Tool execution | `tool-execution / com.jarvis.tools` | `tool-execution/src/main/java/com/jarvis/tools/*` | NOT STARTED |
| REQ-STEP-004 | `isair/jarvis` | Agent control loop | `core-agent / com.jarvis.agent.loop` | `core-agent/src/main/java/com/jarvis/agent/loop/*` | NOT STARTED |
| REQ-STEP-005 | `NousResearch/hermes-agent` | Prompt routing | `core-agent / com.jarvis.agent.routing` | `core-agent/src/main/java/com/jarvis/agent/routing/*` | NOT STARTED |
| REQ-STEP-006 | `NousResearch/hermes-agent` | Task planning | `planning / com.jarvis.planning` | `planning/src/main/java/com/jarvis/planning/*` | NOT STARTED |
| REQ-STEP-007 | Derived from spec capability categories | Retrieval pattern (adapter-only RAG) | `rag / com.jarvis.rag` | `rag/src/main/java/com/jarvis/rag/*` | NOT STARTED |
| REQ-STEP-008 | `open-jarvis/OpenJarvis` (upstream authoritative) | Orchestration | `core-agent / com.jarvis.agent.orchestration` | `core-agent/src/main/java/com/jarvis/agent/orchestration/*` | NOT STARTED |
| REQ-STEP-009 | `open-jarvis/OpenJarvis` (upstream authoritative) | Voice interaction pattern | `speech / com.jarvis.speech` | `speech/src/main/java/com/jarvis/speech/*` | NOT STARTED |
| REQ-STEP-010 | `paperclipai/paperclip` | Plugin/tool adapter pattern (secondary) | `integrations / com.jarvis.integrations` | `integrations/src/main/java/com/jarvis/integrations/*` | NOT STARTED |
| REQ-STEP-011 | N/A (spec public interface requirement) | API surface | `api / com.jarvis.api` | `api/src/main/java/com/jarvis/api/*` | NOT STARTED |
| REQ-STEP-012 | N/A (spec UI inspiration only) | UI inspiration placeholder | `ui / com.jarvis.ui` | `ui/src/main/java/com/jarvis/ui/*` | NOT STARTED |

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
