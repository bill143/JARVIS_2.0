# NEXUS ECHO AI -- CLAUDE.md

## Project Identity

**ECHO Cognitive Runtime v3.0** -- a fully autonomous, self-directed AI operating system for the **ON Estimating Platform** (O'Neill Contractors, Inc., Preconstruction Excellence Division, Glenview, Illinois). ECHO is not a chatbot or copilot. It perceives, reasons, decides, executes, and self-corrects across ten concurrent execution planes without waiting for human instruction.

**Canonical Spec:** `docs/ECHO_SPEC_V3.md` -- the single source of truth for all architectural decisions.

---

## Tech Stack

- **Language:** TypeScript (ES2022, ESM)
- **Runtime:** Node.js >= 20
- **Build:** `tsc` (TypeScript 6.x), `tsx` for dev
- **Path Alias:** `@echo/*` maps to `./echo/*`

### Core Dependencies (by Module)

| Module | Package | Purpose |
|--------|---------|---------|
| 1 - Roaming Brain | `@google/generative-ai` | Gemini 2.5 Pro Multimodal Live API (WebSocket, not REST) |
| 2 - Reasoning Engine | `@langchain/langgraph`, `@langchain/core` | Stateful agent graph + MemorySaver checkpointing |
| 3 - Vision OCR | *(external endpoint)* | Qwen2.5-VL primary, Llama-3.2-11B-Vision fallback |
| 4 - Vault | `@supabase/supabase-js` | pgvector hybrid retrieval (ON_NotebookLM integration) |
| 5 - Operator | `@anthropic-ai/sdk`, `dockerode` | claude-sonnet-4-5 Computer Use in Docker sandbox |
| 6 - Reflexion | *(LangGraph sub-graph)* | Self-audit loop with construction validators |
| 7 - World Feed | `exa-js`, `axios` | Exa + Brave neural search, 7 live API feeds |
| 8 - Permanent Memory | `neo4j-driver`, `@supabase/supabase-js` | Neo4j knowledge graph + pgvector episodic store |
| 9 - Code Interpreter | `@e2b/code-interpreter` | Sandboxed Python/JS execution |
| 10A - Avatar | `simli-client` | Simli.ai real-time WebRTC avatar (HeyGen = async fallback) |
| 10C - Voice | `groq-sdk`, `elevenlabs` | Whisper v3 STT + ElevenLabs Turbo v2.5 TTS |
| Infra | `ws`, `uuid`, `zod` | WebSocket, session IDs, schema validation |

---

## File Architecture

```
/echo/
  /services/
    echo-live.ts          -- Module 1: Gemini Live WebSocket
    echo-operator.ts      -- Module 5: Computer Use sandbox
    echo-world-feed.ts    -- Module 7: Web + API feeds
    echo-avatar.ts        -- Module 10A: Simli.ai avatar engine
    echo-voice.ts         -- Module 10C: Groq STT + ElevenLabs TTS
    /vision/
      echo-vision.ts      -- Module 3: Qwen2.5-VL / Llama Vision

  /agents/
    echo-workflow.ts      -- Module 2: LangGraph StateGraph
    echo-reflexion.ts     -- Module 6: Self-audit loop
    echo-code-runner.ts   -- Module 9: E2B sandboxed interpreter
    /operator/
      BashAgent.ts        -- Terminal commands, scripts, file ops
      ComputerAgent.ts    -- Mouse, keyboard, screen via pixel coords
      EditAgent.ts        -- Surgical file reads/rewrites
      OrchestratorAgent.ts -- Fleet routing + rollback management

  /memory/
    episodic-store.ts     -- pgvector timestamped embeddings
    knowledge-graph.ts    -- Neo4j entity relationship engine
    procedural-store.ts   -- LangGraph saved workflow library

  /validators/
    G702Validator.ts      -- Schedule of Values math verification
    LienWaiverValidator.ts -- Dates, notarization, amount consistency
    COIValidator.ts       -- Coverage limits, expiration, named insured
    DavisBaconValidator.ts -- Wage rates vs published WD tables
    CSICodeValidator.ts   -- 6-digit MasterFormat code validation

  /avatar/
    AvatarStateMachine.ts -- 8-state formal state machine
    GestureLibrary.ts     -- Named gesture to animation ID mapping

  /vault/
    echo-vault.ts         -- ON_NotebookLM retrieval layer

  index.ts                -- Boot entrypoint
```

Other root files: `package.json`, `tsconfig.json`, `.env.example`, `docs/ECHO_SPEC_V3.md`

---

## The Ten Planes

1. **Roaming Brain** -- Gemini 2.5 Pro bidirectional WebSocket (audio + 1FPS vision), <800ms latency
2. **Reasoning Engine** -- LangGraph cyclic graph: PERCEIVE -> REASON -> SURFACE, 30s background polling
3. **Vision OCR** -- Qwen2.5-VL for dropped files only (JPG/PNG/WEBP/PDF/TIFF), returns structured JSON
4. **Vault** -- Mandatory retrieval pre-step for ALL project queries (non-negotiable constraint)
5. **Operator** -- Anthropic Computer Use in Docker sandbox: file system, browser, apps, code, cross-app workflows
6. **Reflexion** -- Self-audit on every response: PASS/WARN/FAIL -> max 3 revision cycles -> human escalation
7. **World Feed** -- SAM.gov, RSMeans, NOAA, Federal Register, OSHA, BLS, UPS/FedEx live connectors
8. **Permanent Memory** -- Episodic (pgvector), Semantic (Neo4j knowledge graph), Procedural (LangGraph workflows)
9. **Code Interpreter** -- E2B sandboxed Python/JS for retention calcs, Davis-Bacon audits, CPM analysis, bid tabs
10. **Digital Human** -- Simli.ai avatar (10A) + 8-state machine (10B) + Groq/ElevenLabs voice (10C) + UI Pulse Portal (10D)

---

## Hard Architectural Constraints

- **Vault grounding is mandatory.** Every response touching specs, scope, estimate line items, or documents must execute a Vault retrieval pass first. Model-weight-only responses for this document class are architecturally prohibited.
- **Reflexion runs on every response.** Draft -> Evaluator -> PASS/WARN/FAIL -> Reflector if failed -> max 3 cycles -> human escalation.
- **Module 1 uses WebSocket only.** Stateless REST is explicitly prohibited for the Roaming Brain.
- **Vision OCR bypasses Gemini.** Dropped files route directly to Qwen2.5-VL, never through the Roaming Brain.
- **Computer Use is sandboxed.** All Operator actions run inside Docker (Xvfb + noVNC), never on host. 3-layer safety gate: intent check -> scope boundary -> rollback buffer.
- **Build order is strict.** Phase 5 (Face Layer / Avatar) never activates until Phases 0-4 are confirmed operational.

---

## Build Phases (Strict Order)

```
Phase 0: SECURITY GATE     -- Session/RBAC, Docker sandbox, Neo4j/pgvector connections, API key validation
Phase 1: KNOWLEDGE LAYER   -- Vault index, episodic memory, knowledge graph hydration, validator warmup
Phase 2: INTELLIGENCE LAYER -- LangGraph compile, Reflexion init, E2B connect, World Feed monitors
Phase 3: PERCEPTION LAYER  -- Gemini Live WebSocket, Vision OCR listeners, Groq Whisper STT
Phase 4: ACTION LAYER      -- Operator fleet online, ElevenLabs TTS pipeline
Phase 5: FACE LAYER        -- Simli.ai WebRTC, avatar model, state machine binding, PCM sync, UI Pulse -> GREEN
```

---

## Required API Keys (7 mandatory + 4 supporting)

See `.env.example` for the complete list. Phase 0 Security Gate refuses to start with missing mandatory keys:
`GEMINI_API_KEY`, `ANTHROPIC_API_KEY`, `SIMLI_API_KEY`, `GROQ_API_KEY`, `ELEVENLABS_API_KEY`, `EXA_API_KEY`, `E2B_API_KEY`

Supporting: `BRAVE_SEARCH_API_KEY`, `NEO4J_*`, `SUPABASE_*`, `HEYGEN_API_KEY` (optional fallback)

---

## UI Pulse Portal (Section 10D)

Bottom-right 80px circular portal with animated pulse ring reflecting cognitive state:

| State | Color | Rhythm |
|-------|-------|--------|
| IDLE | Soft blue | Slow |
| CONNECTING | Amber | Medium |
| ACTIVE | Green | Breathing (synced to speech) |
| THINKING | Purple | Slow wave |
| COMPUTER USE | Orange | Rapid |
| ALERT | Red | Urgent flash |
| DEGRADED | Grey static | Fallback |

Click expands to 1/4 screen half-body view. Double-click for fullscreen immersive mode.

---

## Avatar State Machine (8 States)

IDLE, LISTENING, THINKING, SPEAKING, COMPUTER_USE, ALERT, ANALYTICAL, ERROR, AMBIENT

Each state has deterministic triggers (not sentiment-implied) tied to specific module activity.

---

## Construction Domain Validators

- **G702Validator** -- Schedule of Values column math
- **LienWaiverValidator** -- Dates, notarization fields, amounts
- **COIValidator** -- Coverage limits, expiration dates, named insured
- **DavisBaconValidator** -- Wage rates vs published WD tables
- **CSICodeValidator** -- 6-digit MasterFormat codes vs master list

---

## Development Commands

```bash
npm run dev        # tsx watch echo/index.ts
npm run boot       # tsx echo/index.ts (one-shot)
npm run build      # tsc
npm run start      # node dist/echo/index.js
npm run typecheck  # tsc --noEmit
```

---

## Knowledge Graph Schema (Neo4j)

```
(Project) -[HAS_SUBCONTRACTOR]-> (Subcontractor)
(Subcontractor) -[HOLDS_COI]-> (InsuranceCertificate)
(InsuranceCertificate) -[EXPIRES_ON]-> (Date)
(Project) -[GOVERNED_BY]-> (Contract)
(Contract) -[REFERENCES]-> (FARClause)
(Estimate) -[USES_RATE]-> (DavisBaconWageRate)
(RFI) -[BLOCKS]-> (SubmittalItem)
(SubmittalItem) -[AFFECTS]-> (ScheduleActivity)
```

---

## Key Design Principles

- ECHO acts autonomously -- it does not wait for instruction at every step
- Every factual claim is Vault-grounded with source attribution
- Numbers (dollars, quantities, dates) are tool-validated, not estimated
- Destructive actions create rollback snapshots first
- The avatar is polish, not foundation -- structure before skin
