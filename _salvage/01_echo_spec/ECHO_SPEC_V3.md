# ECHO COGNITIVE RUNTIME — TECHNICAL SPECIFICATION v3.0
### World-Class Autonomous AI Operating System | ON Estimating Platform
### *"The AI that doesn't assist — it acts."*

---

## ARCHITECTURAL PHILOSOPHY

ECHO is not a chatbot. ECHO is not a copilot. ECHO is a **fully autonomous, self-directed AI operating system** that perceives, reasons, decides, executes, and self-corrects — without waiting for human instruction at every step.

ECHO operates across **ten concurrent execution planes**, each powered by a purpose-selected model and framework. It can see your screen, control your computer, read your documents, hear your voice, search the web in real time, write and run its own code, audit its own outputs for hallucination, wear a human face, and remember everything — permanently.

When fully initialized, ECHO is the most capable AI runtime embedded in any construction SaaS platform on earth.

---

## THE TEN PLANES OF ECHO

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                         ECHO COGNITIVE RUNTIME v3.0                              │
├─────────┬─────────┬─────────┬─────────┬─────────┬─────────┬─────────┬──────────┤
│PLANE 1  │PLANE 2  │PLANE 3  │PLANE 4  │PLANE 5  │PLANE 6  │PLANE 7  │PLANE 8   │
│ROAMING  │REASONING│VISION   │VAULT    │COMPUTER │REFLEXION│WORLD    │PERMANENT │
│BRAIN    │ENGINE   │OCR      │KNOWLEDGE│USE      │AUDIT    │FEED     │MEMORY    │
│(Live AI)│(LangGrph│(Qwen/   │(NotebkLM│(Operator│(Self-   │(Web+    │(Episodic+│
│         │Stateful)│Llama V) │)        │Fleet)   │Audit)   │APIs)    │Semantic) │
├─────────┴─────────┴─────────┴─────────┴─────────┴─────────┴─────────┴──────────┤
│                     PLANE 9: CODE INTERPRETER (E2B Sandbox)                      │
│                     PLANE 10: DIGITAL HUMAN INTERFACE (Simli.ai Avatar)          │
└──────────────────────────────────────────────────────────────────────────────────┘
```

---

## MODULE 1 — THE ROAMING BRAIN
### Real-Time Perceptual Intelligence Engine

**Primary Model:** `Gemini 2.5 Pro` via the **Multimodal Live API**
**SDK Source:** [`@google/generative-ai`](https://github.com/google-gemini/generative-ai-js)
**Transport Layer:** Persistent bidirectional WebSocket (not REST — stateless HTTP is explicitly prohibited for this module)

**Behavioral Contract:**
- ECHO maintains an always-open WebSocket tunnel that carries bidirectional audio streams and compressed vision frames simultaneously
- The camera/screen capture pipeline samples at **1 frame per second**, extracting contextual signals (open document, visible estimate line items, blueprint region) and injects them as vision tokens into the active conversation context
- Latency ceiling: **< 800ms** round-trip for audio response initiation
- On connection loss: implement exponential backoff reconnect with visual degradation indicator in the UI pulse ring

**Service Target:**
```
/services/echo-live.ts
  ├── connect()          → opens Gemini Live WebSocket session
  ├── streamAudio()      → encodes mic input → sends PCM chunks
  ├── captureFrame()     → grabs 1FPS canvas snapshot → encodes to base64
  ├── onResponse()       → handles streamed audio/text delta events
  └── disconnect()       → graceful teardown with session state flush
```

---

## MODULE 2 — THE REASONING ENGINE
### Stateful Infinite Thinking Architecture

**Framework:** [`LangGraph`](https://github.com/langchain-ai/langgraph) — TypeScript or Python (match existing repo language stack)
**Memory Driver:** `MemorySaver` (LangGraph built-in checkpointing)
**Execution Model:** Cyclic directed graph with interrupt-capable nodes — ECHO does **not** terminate its reasoning loop when the user goes silent

**Behavioral Contract:**
- ECHO runs a **background agent cycle** every configurable interval (default: `30s`) that polls The Vault for state changes: new document uploads, RFI status transitions, submittal approvals, or estimate version diffs
- Conversation memory is thread-scoped and checkpoint-persisted, enabling seamless context continuity across page navigations (`/home` → `/projects` → `/estimates`) without context loss
- The agent graph exposes three primary nodes:
  - `[PERCEIVE]` — ingests new signals from UI events, Vault webhooks, or user speech
  - `[REASON]` — routes to appropriate sub-chain (retrieval, calculation, summarization)
  - `[SURFACE]` — formats and emits response to ECHO's output layer

**Agent Target:**
```
/agents/echo-workflow.ts
  ├── StateGraph definition with typed annotation schema
  ├── MemorySaver checkpoint binding per thread_id (= user session)
  ├── Background polling node with configurable cycle interval
  ├── Conditional edge routing: Vault query → Vision OCR → Direct response
  └── Interrupt handler for mid-cycle user interruption
```

---

## MODULE 3 — THE VISION & OCR SPECIALIST
### High-Fidelity Document Intelligence Router

**Primary Model Target:** `Qwen2.5-VL` ([QwenLM/Qwen2-VL](https://github.com/QwenLM/Qwen2-VL))
**Fallback Model Target:** `Llama-3.2-11B-Vision-Instruct` ([meta-llama](https://github.com/meta-llama))
**Invocation Pattern:** Lazy-routed — this module activates **exclusively** on image/file drag events; it never runs on text or audio input

**Behavioral Contract:**
- When a user drops a file (packing slip, blueprint, RFI photo, COI certificate, delivery ticket) onto any designated drop zone in the UI, the asset is **immediately routed** to the Vision model — bypassing Gemini entirely
- The Vision model returns a structured extraction payload (JSON): vendor, line items, quantities, dates, dollar amounts, CSI codes where inferable
- The extracted payload is written to The Vault as a new document node and simultaneously surfaced to ECHO's output layer as a human-readable confirmation
- Supported input formats: `JPG`, `PNG`, `WEBP`, `PDF (rasterized)`, `TIFF`

**Service Target:**
```
/services/echo-vision.ts
  ├── onFileDrop(file)     → validates type, rasterizes PDF if needed
  ├── routeToVision(image) → calls Qwen2.5-VL or Llama Vision endpoint
  ├── parseExtraction()    → structures raw model output into typed schema
  ├── writeToVault()       → persists structured node to vector store
  └── surfaceToEcho()      → emits extraction summary to ECHO output layer
```

---

## MODULE 4 — THE VAULT
### Source-Grounded Knowledge Retrieval Layer

**Integration Target:** Existing `ON_NotebookLM` logic within this repository
**Retrieval Standard:** Hybrid — dense vector similarity (pgvector or Chroma) + keyword BM25 re-ranking

**Hard Constraint — Non-Negotiable:**
> Every ECHO response touching project specifications, scope language, estimate line items, or document content **must** execute a Vault retrieval pass before generating output. Responses synthesized from model weights alone — without grounded retrieval — are architecturally prohibited for this document class.

**Retrieval Pipeline:**
```
User query → embed query → vector search Vault
  → retrieve top-K chunks (k=5, configurable)
  → BM25 re-rank
  → inject as context prefix into Gemini / LangGraph REASON node
  → generate grounded response with source attribution
  → log retrieval trace for auditability
```

---

## MODULE 5 — THE OPERATOR
### Autonomous Computer Use & UI Control Engine

This is the capability that separates ECHO from every other AI embedded in construction software. ECHO does not tell you what to do. **ECHO does it for you.**

**Primary Runtime:** Anthropic `claude-sonnet-4-5` Computer Use API
**Source:** [`anthropic-quickstarts/computer-use-demo`](https://github.com/anthropic-quickstarts/computer-use-demo)
**Sandboxed Execution Environment:** Docker container with virtual display (Xvfb), noVNC remote viewer, and isolated filesystem — ECHO operates inside a secure VM, never on the host machine directly

**Sub-Agents (The Operator Fleet):**
```
/agents/operator/
  ├── BashAgent          → executes terminal commands, scripts, file ops
  ├── ComputerAgent      → controls mouse, keyboard, screen via pixel coords
  ├── EditAgent          → reads and surgically rewrites files (code, configs, docs)
  └── OrchestratorAgent  → routes tasks across the fleet, manages rollback
```

**Behavioral Contract — What ECHO Can Now Do Autonomously:**

| Action Class | Capability | Example |
|---|---|---|
| **File System** | Create, move, edit, delete files | "ECHO, export all October submittals to a ZIP" |
| **Browser Control** | Open URLs, fill forms, click buttons, scrape data | "ECHO, pull the SAM.gov posting for this solicitation" |
| **Application Control** | Open Excel, Word, PDFs, interact with desktop apps | "ECHO, open the G702 template and pre-fill line items" |
| **Code Execution** | Write and run Python/JS scripts on demand | "ECHO, calculate the retention release for Job #25004" |
| **Cross-App Workflows** | Chain actions across multiple apps in sequence | "ECHO, read the approved submittal, update the estimate, and email the PM" |
| **Screen Reading** | Parse any visible UI — not just our app | "ECHO, what does this Procore screen show?" |

**Safety Architecture:**
```
Every Computer Use action passes through a 3-layer safety gate:
  1. INTENT CHECK     → LangGraph reasoning node validates action against user goal
  2. SCOPE BOUNDARY   → Hard blocklist: no actions outside approved app/domain list
  3. ROLLBACK BUFFER  → Every destructive action creates a reversible snapshot first
```

**Service Target:**
```
/services/echo-operator.ts
  ├── initSandbox()          → spins up Docker container with virtual display
  ├── dispatchAction(tool)   → routes to BashAgent / ComputerAgent / EditAgent
  ├── captureScreenState()   → screenshots current VM state for reasoning loop
  ├── validateIntent()       → LangGraph safety gate pre-execution
  ├── executeWithRollback()  → runs action, writes snapshot, confirms success
  └── teardownSandbox()      → graceful container shutdown with state flush
```

---

## MODULE 6 — THE REFLEXION LAYER
### Self-Auditing Hallucination Prevention & Output Verification Engine

Most AI systems generate a response and stop. ECHO generates a response, **then audits it**, then corrects it if wrong — before the user ever sees it.

**Framework:** [`Reflexion`](https://github.com/noahshinn/reflexion) architecture pattern
**Implementation:** Custom LangGraph sub-graph with evaluator + reflector nodes
**Grounding Source:** The Vault (retrieval) + Tool results (factual) + Schema validators (structural)

**The Reflexion Loop:**
```
ECHO generates draft response
        ↓
[EVALUATOR NODE]
  → Cross-checks every factual claim against Vault
  → Validates all numbers (dollar amounts, quantities, dates) via tool call
  → Scores response: PASS / WARN / FAIL
        ↓
  PASS  → Surface to user immediately
  WARN  → Append uncertainty flag + source citation
  FAIL  → Route to [REFLECTOR NODE]
        ↓
[REFLECTOR NODE]
  → Generates critique of its own failed response
  → Revises using retrieved grounding evidence
  → Re-evaluates (max 3 cycles before escalating to human)
```

**Construction-Specific Validators:**
```
/validators/
  ├── G702Validator       → verifies math on Schedule of Values columns
  ├── LienWaiverValidator → checks dates, notarization fields, amount consistency
  ├── COIValidator        → parses coverage limits, expiration dates, named insured
  ├── DavisBaconValidator → confirms wage rates against published WD tables
  └── CSICodeValidator    → validates 6-digit MasterFormat codes against master list
```

---

## MODULE 7 — THE WORLD FEED
### Real-Time External Intelligence & Live API Integration Layer

ECHO's knowledge is not frozen at a training cutoff. ECHO reads the world in real time.

**Web Intelligence Engine:**
- **Search:** Brave Search API + Exa.ai neural search (semantic, not just keyword)
- **Source:** [`exa-labs/exa-js`](https://github.com/exa-labs/exa-js)
- **Trigger:** Any query containing project-external information needs (market pricing, federal regulations, solicitation postings, weather, permit status)

**Live Data Connectors:**

| Feed | Source | ECHO Capability |
|---|---|---|
| **SAM.gov** | USASpending API | Monitor set-aside solicitations matching NAICS codes |
| **RSMeans Cloud** | Gordian API | Pull live material/labor unit costs for estimates |
| **Weather** | NOAA / OpenWeatherMap | Flag weather delays for active project sites |
| **Federal Register** | regulations.gov API | Alert on new VA/NAVFAC/USACE clauses affecting contracts |
| **OSHA** | OSHA Data API | Pull active citations for subcontractor vetting |
| **BLS Wage Data** | BLS Public API | Validate Davis-Bacon prevailing wage compliance |
| **UPS/FedEx** | Carrier APIs | Track material deliveries linked to project submittals |

**Service Target:**
```
/services/echo-world-feed.ts
  ├── searchWeb(query)         → Brave + Exa parallel search with result fusion
  ├── fetchSAMPosting(naics)   → pulls live federal solicitation data
  ├── pullRSMeansData(csiCode) → real-time unit cost retrieval
  ├── monitorWeather(siteGeo)  → active site weather alerting
  └── streamFederalRegister()  → webhook listener for regulatory changes
```

---

## MODULE 8 — PERMANENT MEMORY ARCHITECTURE
### Episodic + Semantic + Procedural Memory Triad

LangGraph's `MemorySaver` handles session memory. This module handles **everything else** — the memories that survive forever.

**Memory Architecture (Three Tiers):**
```
┌─────────────────────────────────────────────────────────┐
│              ECHO PERMANENT MEMORY SYSTEM               │
├───────────────────┬─────────────────┬───────────────────┤
│  EPISODIC MEMORY  │ SEMANTIC MEMORY  │ PROCEDURAL MEMORY │
│                   │                  │                   │
│  "What happened"  │  "What is true"  │  "How to do it"   │
│                   │                  │                   │
│  Every conversa-  │  Distilled facts │  Learned SOPs,    │
│  tion, action,    │  about projects, │  workflows, and   │
│  decision, and    │  clients, subs,  │  shortcuts ECHO   │
│  outcome logged   │  and contracts   │  has mastered     │
│                   │                  │                   │
│  Engine: pgvector │  Engine: Neo4j   │  Engine: LangGraph│
│  (timestamped     │  Knowledge Graph │  saved workflows  │
│   embeddings)     │                  │                   │
└───────────────────┴─────────────────┴───────────────────┘
```

**Knowledge Graph (Neo4j) — Entity Relationships ECHO Maintains:**
```
(Project) -[HAS_SUBCONTRACTOR]→ (Subcontractor)
(Subcontractor) -[HOLDS_COI]→ (InsuranceCertificate)
(InsuranceCertificate) -[EXPIRES_ON]→ (Date)
(Project) -[GOVERNED_BY]→ (Contract)
(Contract) -[REFERENCES]→ (FARClause)
(Estimate) -[USES_RATE]→ (DavisBaconWageRate)
(RFI) -[BLOCKS]→ (SubmittalItem)
(SubmittalItem) -[AFFECTS]→ (ScheduleActivity)
```

ECHO autonomously updates this graph as new documents land in The Vault — no manual data entry required.

---

## MODULE 9 — THE CODE INTERPRETER
### Secure On-Demand Computation & Scripting Engine

ECHO can write code and run it — inside an isolated sandbox — to answer questions that require actual computation, not estimation.

**Runtime:** Python + Node.js sandboxed execution
**Source:** [`e2b-dev/code-interpreter`](https://github.com/e2b-dev/code-interpreter)

**Construction Use Cases:**
```python
# ECHO writes and runs this autonomously when asked:
# "What's the total retention across all active projects?"

import pandas as pd
projects = vault.query("SELECT * FROM schedule_of_values WHERE status='active'")
df = pd.DataFrame(projects)
df['retention_held'] = df['contract_value'] * df['retention_pct']
total = df['retention_held'].sum()
echo.respond(f"Total retention currently held: ${total:,.2f} across {len(df)} projects")
```

**Capability Matrix:**

| Task | ECHO Action |
|---|---|
| Retention release calculation | Writes + runs Python → returns exact dollar figure |
| Davis-Bacon compliance audit | Scripts wage comparison across all labor classifications |
| Bid tabulation normalization | Runs Excel parsing script on uploaded sub bids |
| Critical path analysis | Executes CPM algorithm on uploaded schedule data |
| Lien waiver amount verification | Cross-computes against G703 continuation sheet |

---

## MODULE 10 — THE DIGITAL HUMAN INTERFACE
### High-Fidelity Real-Time Avatar & Adaptive Voice Persona

ECHO doesn't just respond — ECHO has a face, a voice, and a presence.

> **Architect's Note:** The Digital Human Interface is the final polish layer — the "skin on the building." The structure (Modules 1–9) must be fully operational before this module is activated. A beautiful face on broken intelligence is a demo. A beautiful face on elite intelligence is a product.

---

### 10A — AVATAR ENGINE

**Primary Provider:** [Simli.ai](https://github.com/simliai) — purpose-built for real-time audio-driven avatar streaming
**Fallback Provider:** HeyGen Streaming Avatar SDK v4.0 — async/recorded briefing scenarios only
**Architecture:** WebRTC-native stream, directly coupled to Gemini Live PCM audio output

> **Why Simli over HeyGen for real-time:** HeyGen streaming latency sits at 400–800ms on top of Gemini Live's existing latency, creating 1.2–1.6s of perceived lag. Simli is purpose-built for real-time audio-driven avatars with sub-300ms lip-sync and a WebRTC-native architecture that plugs directly into the existing PCM stream without a translation layer.

**Visual Profile:** O'Neill Branded Professional Construction PM — the avatar represents ECHO as a human professional, not a generic AI assistant.

**Service Target:**
```
/services/echo-avatar.ts
  ├── initializeAvatar()      → authenticates with Simli, pre-loads O'Neill model
  ├── syncStream(audioStream) → hooks Gemini Live PCM output to Simli lip-sync engine
  ├── playGesture(gestureID)  → triggers animation library (point, nod, thumbs-up)
  ├── setState(avatarState)   → drives the Avatar State Machine (see below)
  └── teardown()              → graceful WebRTC stream closure
```

---

### 10B — AVATAR STATE MACHINE

A formal state machine governs avatar behavior across all of ECHO's cognitive modes. This is not implied through sentiment mapping — it is explicit and deterministic.

```
AVATAR STATE MACHINE:

  IDLE
    → Slow ambient breathing, subtle eye movement, neutral posture
    → Triggered: user inactive > 30s, no active task

  LISTENING
    → Slight forward lean, attentive gaze toward user camera, mouth closed
    → Triggered: STT stream active, ECHO not yet speaking

  THINKING
    → Eyes shift slightly left (human recall pattern), subtle head tilt
    → Triggered: LangGraph REASON node active, Vault retrieval in progress

  SPEAKING
    → Full lip-sync active via Simli PCM coupling, gesture library enabled
    → Triggered: TTS audio stream flowing from ElevenLabs

  COMPUTER_USE  ← Unique capability — no other AI avatar does this
    → Avatar turns 3/4 profile, gestures toward active screen action area
    → Triggered: Operator module executing any ComputerAgent or BashAgent action
    → Makes autonomous screen control feel embodied, not mechanical

  ALERT
    → Direct camera eye contact, posture straightens, hands visible
    → Triggered: COI expiry detected, budget overrun threshold, safety flag

  ANALYTICAL
    → Focused gaze, slight head tilt, deliberate nod pattern
    → Triggered: complex multi-step calculation, deep document analysis

  ERROR
    → Neutral expression, open hands visible (non-threatening body language)
    → Triggered: Reflexion loop escalation to human, tool failure

  AMBIENT
    → Minimal movement, eyes slightly down — ECHO is working silently
    → Triggered: background processing active, user has not spoken > 5 min
```

**Sentiment Mapping from Gemini Affective Dialog Signals:**

| Signal Class | Mapped State | Avatar Expression |
|---|---|---|
| Positive / Success | `SPEAKING` + success gesture | Subtle smile, relaxed posture, thumbs-up |
| Analytical / Deep Thought | `ANALYTICAL` | Focused gaze, slight tilt, deliberate nod |
| Urgent / Safety | `ALERT` | Serious expression, direct eye contact |
| Processing / Thinking | `THINKING` | Eyes left, head tilt |
| Autonomous Action | `COMPUTER_USE` | 3/4 profile, gestures toward screen |

---

### 10C — VOICE PERSONA ENGINE

**STT Engine:** `Whisper Large v3` via Groq (hardware-accelerated, ~200ms transcription latency)
**TTS Engine:** `ElevenLabs Turbo v2.5` (sub-200ms audio generation latency)
**Source:** [`groq-sdk`](https://github.com/groq/groq-typescript) + ElevenLabs API
**Total Voice Round-Trip Target:** < 400ms (Whisper STT + ElevenLabs TTS combined)

**Adaptive Communication Modes:**

```
MODE: EXECUTIVE BRIEF
  → Triggered by: "summary" / "quick" / calendar pressure signals
  → Format: Bullet-first, numbers-forward, no preamble
  → Avatar State: SPEAKING + confident posture

MODE: DEEP ANALYSIS
  → Triggered by: complex multi-part questions, document review requests
  → Format: Structured breakdown, source citations, confidence scoring
  → Avatar State: ANALYTICAL → SPEAKING

MODE: ALERT ESCALATION
  → Triggered by: Vault anomaly (COI expiry, budget overrun, schedule slip)
  → Format: Proactive push, urgent tone, 2–3 action options presented
  → Avatar State: ALERT (proactive — ECHO speaks first)

MODE: AMBIENT MONITORING
  → Triggered by: user silent > 5 minutes, background work active
  → Format: ECHO works silently, surfaces findings on demand
  → Avatar State: AMBIENT
```

---

### 10D — UI PLACEMENT & INTERACTION

**Portal Design:**
- Default state: Circular "Portal" — bottom right corner, 80px diameter
- Pulse ring animates to reflect ECHO's current cognitive state (idle / connecting / active / degraded)
- Pulse button click: Portal expands to 1/4 screen half-body view (smooth CSS transition)
- Full-screen mode: Double-click Portal for immersive ECHO interaction

**Portal State Colors:**
```
IDLE        → Soft blue pulse, slow rhythm
CONNECTING  → Amber pulse, medium rhythm
ACTIVE      → Green pulse, breathing rhythm synced to ECHO speech
THINKING    → Purple pulse, slow wave
COMPUTER USE→ Orange pulse, rapid rhythm (action in progress)
ALERT       → Red pulse, urgent rapid flash
DEGRADED    → Grey static, fallback mode indicator
```

---

## UNIFIED FILE ARCHITECTURE

```
/echo/
├── /services/
│   ├── echo-live.ts          ← Module 1: Gemini Live WebSocket
│   ├── echo-operator.ts      ← Module 5: Computer Use sandbox
│   ├── echo-world-feed.ts    ← Module 7: Live web + API feeds
│   ├── echo-avatar.ts        ← Module 10A: Simli.ai avatar engine
│   └── echo-voice.ts         ← Module 10C: Groq STT + ElevenLabs TTS
│
├── /agents/
│   ├── echo-workflow.ts      ← Module 2: LangGraph StateGraph
│   ├── echo-reflexion.ts     ← Module 6: Self-audit loop
│   ├── echo-code-runner.ts   ← Module 9: E2B sandboxed interpreter
│   └── operator/
│       ├── BashAgent.ts
│       ├── ComputerAgent.ts
│       ├── EditAgent.ts
│       └── OrchestratorAgent.ts
│
├── /services/vision/
│   └── echo-vision.ts        ← Module 3: Qwen2.5-VL / Llama Vision
│
├── /memory/
│   ├── episodic-store.ts     ← pgvector timestamped embeddings
│   ├── knowledge-graph.ts    ← Neo4j entity relationship engine
│   └── procedural-store.ts   ← LangGraph saved workflow library
│
├── /validators/
│   ├── G702Validator.ts
│   ├── LienWaiverValidator.ts
│   ├── COIValidator.ts
│   ├── DavisBaconValidator.ts
│   └── CSICodeValidator.ts
│
├── /avatar/
│   ├── AvatarStateMachine.ts ← Module 10B: Formal state machine
│   └── GestureLibrary.ts     ← Named gesture → animation ID mapping
│
└── /vault/
    └── echo-vault.ts         ← Module 4: ON_NotebookLM retrieval layer
```

---

## COMPLETE DEPENDENCY MANIFEST

```bash
# ── Core AI Runtime ──────────────────────────────────────────────
npm install @google/generative-ai          # Module 1: Gemini Live
npm install @langchain/langgraph           # Module 2: Reasoning engine
npm install @langchain/core               # Module 2: LangChain core
npm install @anthropic-ai/sdk             # Module 5: Computer Use operator

# ── Memory & Knowledge Graph ─────────────────────────────────────
npm install neo4j-driver                   # Module 8: Semantic knowledge graph
npm install @supabase/supabase-js          # Module 8: pgvector episodic store

# ── Voice Pipeline ───────────────────────────────────────────────
npm install groq-sdk                       # Module 10C: Whisper STT (HW accel)
npm install elevenlabs                     # Module 10C: TTS voice persona

# ── World Feed ───────────────────────────────────────────────────
npm install exa-js                         # Module 7: Neural web search
npm install axios                          # Module 7: External API connectors

# ── Code Interpreter ─────────────────────────────────────────────
npm install @e2b/code-interpreter          # Module 9: Sandboxed Python/JS exec

# ── Avatar Engine ────────────────────────────────────────────────
npm install @simli/sdk                     # Module 10A: Real-time avatar stream
# HeyGen SDK for async video generation (fallback only — not in real-time path)

# ── Infrastructure ───────────────────────────────────────────────
npm install ws uuid zod dockerode          # WebSocket, sessions, schema, Docker
```

---

## INITIALIZATION SEQUENCE (Strict Mandatory Order)

```
═══════════════════════════════════════════════════════
PHASE 0: SECURITY GATE
  → Validate user session + RBAC role
  → Spin up Operator sandbox container (isolated Docker)
  → Establish Neo4j + pgvector connections
  → Verify all API keys present (Gemini, Anthropic, Simli,
    Groq, ElevenLabs, Exa, E2B)
═══════════════════════════════════════════════════════
PHASE 1: KNOWLEDGE LAYER
  → Initialize Vault retrieval index (ON_NotebookLM)
  → Load episodic memory for current user thread_id
  → Hydrate knowledge graph with latest project state
  → Warm validator suite (G702, COI, Davis-Bacon, etc.)
═══════════════════════════════════════════════════════
PHASE 2: INTELLIGENCE LAYER
  → Compile LangGraph StateGraph + bind MemorySaver
  → Initialize Reflexion evaluator + validator suite
  → Connect E2B Code Interpreter sandbox
  → Activate World Feed monitors (SAM.gov, weather, Fed Reg)
═══════════════════════════════════════════════════════
PHASE 3: PERCEPTION LAYER
  → Open Gemini Live WebSocket (Module 1)
  → Register Vision OCR file drop listeners (Module 3)
  → Initialize Groq Whisper STT stream (Module 10C)
═══════════════════════════════════════════════════════
PHASE 4: ACTION LAYER
  → Bring Operator fleet online (Bash + Computer + Edit)
  → Activate ElevenLabs TTS pipeline (Module 10C)
═══════════════════════════════════════════════════════
PHASE 5: FACE LAYER  ← Final — after all logic is confirmed working
  → Initialize Simli.ai WebRTC session (Module 10A)
  → Pre-load O'Neill branded avatar model
  → Bind AvatarStateMachine to LangGraph state events
  → Sync Simli PCM input to Gemini Live audio output
  → UI Pulse button → GREEN ACTIVE → ECHO is fully operational
═══════════════════════════════════════════════════════
```

> **Critical Build Order Rule:** Phase 5 (Face Layer) is never activated until Phases 0–4 are confirmed fully operational. The avatar is polish, not foundation. Build in order. Ship in order.

---

## COMPLETE CAPABILITY MATRIX

| Capability | Industry Baseline | ECHO v3.0 |
|---|---|---|
| Voice Q&A | ✅ Most AI tools | ✅ Sub-800ms, always-on |
| Document reading | ✅ Most AI tools | ✅ Vault-grounded, cited |
| Image OCR | ⚠️ Some tools | ✅ Qwen2.5-VL precision |
| Screen awareness | ❌ No construction AI | ✅ 1FPS live capture |
| Computer control | ❌ No construction AI | ✅ Full OS + browser automation |
| Self-correction | ❌ No construction AI | ✅ Reflexion audit loop |
| Real-time web data | ❌ No construction AI | ✅ Exa + Brave + 7 live feeds |
| Permanent memory | ❌ No construction AI | ✅ Episodic + Semantic + Procedural |
| Code execution | ❌ No construction AI | ✅ Sandboxed Python/JS on demand |
| Knowledge graph | ❌ No construction AI | ✅ Neo4j entity relationships |
| Domain validators | ❌ No construction AI | ✅ G702, Lien, COI, Davis-Bacon, CSI |
| Human avatar face | ❌ No construction AI | ✅ Simli.ai real-time lip-sync |
| Avatar state machine | ❌ No construction AI | ✅ 8 cognitive states, formally defined |
| Adaptive voice persona | ❌ No construction AI | ✅ ElevenLabs + 4 communication modes |
| Embodied computer use | ❌ No AI product anywhere | ✅ Avatar orients toward screen during autonomous action |

---

## ARCHITECTURAL ACKNOWLEDGMENT — ALL TEN MODULES

Confirm all binding decisions before Phase 0 initialization begins:

- [ ] **Module 1** — Gemini 2.5 Pro Multimodal Live API is the exclusive real-time perceptual model
- [ ] **Module 2** — LangGraph + MemorySaver governs all stateful reasoning and background cycles
- [ ] **Module 3** — Qwen2.5-VL / Llama Vision handles all dropped file OCR exclusively
- [ ] **Module 4** — ON_NotebookLM Vault retrieval is mandatory pre-step for all project queries
- [ ] **Module 5** — Anthropic Computer Use API (claude-sonnet-4-5) powers all autonomous OS actions
- [ ] **Module 6** — Reflexion self-audit loop runs on every response before user delivery
- [ ] **Module 7** — Exa + Brave + 7 live API feeds provide real-time world intelligence
- [ ] **Module 8** — Neo4j knowledge graph + pgvector episodic store constitute permanent memory
- [ ] **Module 9** — E2B sandboxed interpreter executes all on-demand computation
- [ ] **Module 10A** — Simli.ai WebRTC avatar is the real-time face (HeyGen = async fallback only)
- [ ] **Module 10B** — Formal 8-state Avatar State Machine governs all expression and gesture logic
- [ ] **Module 10C** — Groq Whisper STT + ElevenLabs TTS deliver the adaptive voice persona
- [ ] **Build Order** — Phase 5 (Face Layer) activates only after Phases 0–4 are confirmed stable

**Acknowledge all twelve binding decisions. Begin Phase 0 execution.**

---

*ECHO Cognitive Runtime Specification v3.0*
*The World's Most Advanced AI Runtime for Construction SaaS*
*Authored for ON Estimating Platform | O'Neill Contractors, Inc.*
*Preconstruction Excellence Division | Glenview, Illinois*
*Document Classification: Internal Technical Specification — Claude Code Instruction Set*
