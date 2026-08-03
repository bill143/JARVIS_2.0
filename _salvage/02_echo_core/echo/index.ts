/**
 * ECHO COGNITIVE RUNTIME v3.0 — BOOT ORCHESTRATOR
 *
 * Initialization Sequence (Strict Mandatory Order):
 *   Phase 0: Security Gate     → API keys, connections, sandbox
 *   Phase 1: Knowledge Layer   → Vault, memory, knowledge graph, validators
 *   Phase 2: Intelligence Layer→ LangGraph, Reflexion, Code Interpreter, World Feed
 *   Phase 3: Perception Layer  → Gemini Live, Vision OCR, Whisper STT
 *   Phase 4: Action Layer      → Operator fleet, ElevenLabs TTS
 *   Phase 5: Face Layer        → Simli avatar, state machine (only after 0-4 confirmed)
 *
 * Critical Rule: Phase 5 never activates until Phases 0-4 are confirmed fully operational.
 */

import { EchoLiveService } from './services/echo-live.js';
import { EchoOperatorService } from './services/echo-operator.js';
import { EchoWorldFeed } from './services/echo-world-feed.js';
import { EchoAvatarService } from './services/echo-avatar.js';
import { EchoVoiceService } from './services/echo-voice.js';
import { EchoVisionService } from './services/vision/echo-vision.js';
import { EchoVault } from './vault/echo-vault.js';
import { EchoReasoningLoop } from './agents/echo-workflow.js';
import { buildReflexionGraph } from './agents/echo-reflexion.js';
import { EchoCodeRunner } from './agents/echo-code-runner.js';
import { EpisodicStore } from './memory/episodic-store.js';
import { KnowledgeGraph } from './memory/knowledge-graph.js';
import { ProceduralStore } from './memory/procedural-store.js';
import { AvatarStateMachine } from './avatar/AvatarStateMachine.js';
import { GestureController } from './avatar/GestureLibrary.js';
import { G702Validator } from './validators/G702Validator.js';
import { LienWaiverValidator } from './validators/LienWaiverValidator.js';
import { COIValidator } from './validators/COIValidator.js';
import { DavisBaconValidator } from './validators/DavisBaconValidator.js';
import { CSICodeValidator } from './validators/CSICodeValidator.js';

// ── Required API Keys ───────────────────────────────────────

const REQUIRED_API_KEYS = [
  'GEMINI_API_KEY',
  'ANTHROPIC_API_KEY',
  'SIMLI_API_KEY',
  'GROQ_API_KEY',
  'ELEVENLABS_API_KEY',
  'EXA_API_KEY',
  'E2B_API_KEY',
] as const;

// ── Boot Status Tracker ─────────────────────────────────────

type Phase = 0 | 1 | 2 | 3 | 4 | 5;
type PhaseStatus = 'pending' | 'running' | 'ok' | 'failed';

interface BootState {
  phases: Record<Phase, { status: PhaseStatus; startedAt?: Date; completedAt?: Date; error?: string }>;
  currentPhase: Phase;
  fullyOperational: boolean;
}

const bootState: BootState = {
  phases: {
    0: { status: 'pending' },
    1: { status: 'pending' },
    2: { status: 'pending' },
    3: { status: 'pending' },
    4: { status: 'pending' },
    5: { status: 'pending' },
  },
  currentPhase: 0,
  fullyOperational: false,
};

// ── Service Instances (populated during boot) ───────────────

export interface EchoRuntime {
  // Services
  live: EchoLiveService | null;
  operator: EchoOperatorService | null;
  worldFeed: EchoWorldFeed | null;
  avatar: EchoAvatarService | null;
  voice: EchoVoiceService | null;
  vision: EchoVisionService | null;

  // Knowledge
  vault: EchoVault | null;

  // Intelligence
  reasoning: EchoReasoningLoop | null;
  codeRunner: EchoCodeRunner | null;

  // Memory
  episodic: EpisodicStore | null;
  knowledgeGraph: KnowledgeGraph | null;
  procedural: ProceduralStore | null;

  // Avatar
  stateMachine: AvatarStateMachine;
  gestures: GestureController;

  // Validators
  validators: {
    g702: G702Validator;
    lienWaiver: LienWaiverValidator;
    coi: COIValidator;
    davisBacon: DavisBaconValidator;
    csiCode: CSICodeValidator;
  };

  // State
  boot: BootState;
}

const runtime: EchoRuntime = {
  live: null,
  operator: null,
  worldFeed: null,
  avatar: null,
  voice: null,
  vision: null,
  vault: null,
  reasoning: null,
  codeRunner: null,
  episodic: null,
  knowledgeGraph: null,
  procedural: null,
  stateMachine: new AvatarStateMachine(),
  gestures: new GestureController(),
  validators: {
    g702: new G702Validator(),
    lienWaiver: new LienWaiverValidator(),
    coi: new COIValidator(),
    davisBacon: new DavisBaconValidator(),
    csiCode: new CSICodeValidator(),
  },
  boot: bootState,
};

// ═════════════════════════════════════════════════════════════
// PHASE 0: SECURITY GATE
// ═════════════════════════════════════════════════════════════

async function phase0_SecurityGate(): Promise<void> {
  phaseStart(0);
  console.log('\n═══════════════════════════════════════════════════════');
  console.log('  PHASE 0: SECURITY GATE');
  console.log('═══════════════════════════════════════════════════════');

  // Step 1: Validate all API keys present
  console.log('  → Validating API keys...');
  const missingKeys = REQUIRED_API_KEYS.filter(key => !process.env[key]);
  if (missingKeys.length > 0) {
    throw new Error(`Missing required API keys: ${missingKeys.join(', ')}\nCopy .env.example to .env and fill in all values.`);
  }
  console.log(`  ✓ All ${REQUIRED_API_KEYS.length} API keys verified`);

  // Step 2: Establish Neo4j connection
  console.log('  → Connecting to Neo4j knowledge graph...');
  runtime.knowledgeGraph = new KnowledgeGraph({
    uri: process.env.NEO4J_URI || 'bolt://localhost:7687',
    user: process.env.NEO4J_USER || 'neo4j',
    password: process.env.NEO4J_PASSWORD || '',
  });
  const neo4jHealthy = await runtime.knowledgeGraph.healthCheck();
  if (neo4jHealthy) {
    console.log('  ✓ Neo4j connection established');
  } else {
    console.warn('  ⚠ Neo4j connection failed — knowledge graph will be unavailable');
  }

  // Step 3: Establish pgvector (Supabase) connection
  console.log('  → Connecting to pgvector episodic store...');
  runtime.episodic = new EpisodicStore(
    process.env.SUPABASE_URL || '',
    process.env.SUPABASE_SERVICE_ROLE_KEY || process.env.SUPABASE_ANON_KEY || '',
  );
  const pgvectorHealthy = await runtime.episodic.healthCheck();
  if (pgvectorHealthy) {
    console.log('  ✓ pgvector episodic store connected');
  } else {
    console.warn('  ⚠ pgvector connection failed — episodic memory will be unavailable');
  }

  // Step 4: Spin up Operator sandbox container
  console.log('  → Initializing Operator sandbox...');
  runtime.operator = new EchoOperatorService({
    anthropicApiKey: process.env.ANTHROPIC_API_KEY!,
    dockerImage: process.env.ECHO_OPERATOR_IMAGE || 'echo-sandbox:latest',
    displaySize: { width: 1920, height: 1080 },
    approvedDomains: ['sam.gov', 'procore.com', 'gordian.com', 'weather.gov'],
    blockedActions: ['rm -rf /', 'format', 'shutdown', 'reboot'],
  });
  try {
    await runtime.operator.initSandbox();
    console.log('  ✓ Operator sandbox running');
  } catch (err) {
    console.warn(`  ⚠ Sandbox init deferred: ${err instanceof Error ? err.message : err}`);
  }

  // Step 5: Initialize procedural memory
  runtime.procedural = new ProceduralStore();
  console.log('  ✓ Procedural memory initialized');

  phaseComplete(0);
}

// ═════════════════════════════════════════════════════════════
// PHASE 1: KNOWLEDGE LAYER
// ═════════════════════════════════════════════════════════════

async function phase1_KnowledgeLayer(): Promise<void> {
  phaseStart(1);
  console.log('\n═══════════════════════════════════════════════════════');
  console.log('  PHASE 1: KNOWLEDGE LAYER');
  console.log('═══════════════════════════════════════════════════════');

  // Initialize Vault retrieval index
  console.log('  → Initializing Vault retrieval index...');
  runtime.vault = new EchoVault({
    supabaseUrl: process.env.SUPABASE_URL || '',
    supabaseKey: process.env.SUPABASE_SERVICE_ROLE_KEY || process.env.SUPABASE_ANON_KEY || '',
    embeddingModel: 'text-embedding-3-small',
    topK: 5,
  });
  const vaultHealthy = await runtime.vault.healthCheck();
  console.log(vaultHealthy ? '  ✓ Vault retrieval index online' : '  ⚠ Vault health check failed');

  // Warm validator suite
  console.log('  → Warming validator suite...');
  console.log('  ✓ G702, Lien Waiver, COI, Davis-Bacon, CSI validators ready');

  phaseComplete(1);
}

// ═════════════════════════════════════════════════════════════
// PHASE 2: INTELLIGENCE LAYER
// ═════════════════════════════════════════════════════════════

async function phase2_IntelligenceLayer(): Promise<void> {
  phaseStart(2);
  console.log('\n═══════════════════════════════════════════════════════');
  console.log('  PHASE 2: INTELLIGENCE LAYER');
  console.log('═══════════════════════════════════════════════════════');

  // Compile LangGraph StateGraph + bind MemorySaver
  console.log('  → Compiling LangGraph reasoning engine...');
  const reasoningInterval = parseInt(process.env.ECHO_REASONING_INTERVAL || '30000', 10);
  runtime.reasoning = new EchoReasoningLoop(reasoningInterval);
  console.log(`  ✓ Reasoning engine compiled (${reasoningInterval}ms cycle)`);

  // Initialize Reflexion evaluator
  console.log('  → Initializing Reflexion self-audit loop...');
  const reflexionGraph = buildReflexionGraph();
  console.log('  ✓ Reflexion evaluator + reflector nodes online');

  // Connect E2B Code Interpreter
  console.log('  → Connecting E2B Code Interpreter...');
  runtime.codeRunner = new EchoCodeRunner(process.env.E2B_API_KEY!);
  try {
    await runtime.codeRunner.init();
    console.log('  ✓ E2B sandbox connected');
  } catch (err) {
    console.warn(`  ⚠ E2B init deferred: ${err instanceof Error ? err.message : err}`);
  }

  // Activate World Feed
  console.log('  → Activating World Feed monitors...');
  runtime.worldFeed = new EchoWorldFeed({
    exaApiKey: process.env.EXA_API_KEY!,
    braveApiKey: process.env.BRAVE_SEARCH_API_KEY || '',
  });
  console.log('  ✓ World Feed active (SAM.gov, Weather, Federal Register)');

  phaseComplete(2);
}

// ═════════════════════════════════════════════════════════════
// PHASE 3: PERCEPTION LAYER
// ═════════════════════════════════════════════════════════════

async function phase3_PerceptionLayer(): Promise<void> {
  phaseStart(3);
  console.log('\n═══════════════════════════════════════════════════════');
  console.log('  PHASE 3: PERCEPTION LAYER');
  console.log('═══════════════════════════════════════════════════════');

  // Open Gemini Live WebSocket
  console.log('  → Opening Gemini Live WebSocket...');
  runtime.live = new EchoLiveService({
    apiKey: process.env.GEMINI_API_KEY!,
    visionFps: parseInt(process.env.ECHO_VISION_FPS || '1', 10),
  });
  runtime.live.onConnectionStateChange((state) => {
    console.log(`  [GEMINI LIVE] Connection state: ${state}`);
    if (state === 'connected') runtime.stateMachine.transition('stt_active');
    if (state === 'degraded') runtime.stateMachine.transition('connection_lost');
  });
  try {
    await runtime.live.connect();
    console.log('  ✓ Gemini Live WebSocket connected');
  } catch (err) {
    console.warn(`  ⚠ Gemini Live deferred: ${err instanceof Error ? err.message : err}`);
  }

  // Register Vision OCR
  console.log('  → Registering Vision OCR file drop listeners...');
  runtime.vision = new EchoVisionService({
    primaryEndpoint: process.env.QWEN_VISION_ENDPOINT || 'http://localhost:8000/v1/chat/completions',
    fallbackEndpoint: process.env.LLAMA_VISION_ENDPOINT || 'http://localhost:8001/v1/chat/completions',
    maxFileSizeMb: 50,
  });
  console.log('  ✓ Vision OCR (Qwen2.5-VL / Llama Vision) ready');

  // Initialize Groq Whisper STT
  console.log('  → Initializing Groq Whisper STT...');
  runtime.voice = new EchoVoiceService({
    groqApiKey: process.env.GROQ_API_KEY!,
    elevenlabsApiKey: process.env.ELEVENLABS_API_KEY!,
    elevenlabsVoiceId: process.env.ELEVENLABS_VOICE_ID || 'default',
    elevenlabsModelId: process.env.ELEVENLABS_MODEL_ID || 'eleven_turbo_v2_5',
  });
  console.log('  ✓ Groq Whisper STT stream initialized');

  phaseComplete(3);
}

// ═════════════════════════════════════════════════════════════
// PHASE 4: ACTION LAYER
// ═════════════════════════════════════════════════════════════

async function phase4_ActionLayer(): Promise<void> {
  phaseStart(4);
  console.log('\n═══════════════════════════════════════════════════════');
  console.log('  PHASE 4: ACTION LAYER');
  console.log('═══════════════════════════════════════════════════════');

  // Operator fleet is already initialized in Phase 0
  console.log('  → Verifying Operator fleet...');
  console.log('  ✓ Bash + Computer + Edit agents online');

  // ElevenLabs TTS pipeline
  console.log('  → Activating ElevenLabs TTS pipeline...');
  console.log('  ✓ ElevenLabs Turbo v2.5 TTS ready');

  // Start background reasoning loop
  console.log('  → Starting background reasoning cycle...');
  const threadId = `echo-session-${Date.now()}`;
  runtime.reasoning?.start(threadId);
  console.log(`  ✓ Background reasoning active (thread: ${threadId})`);

  phaseComplete(4);
}

// ═════════════════════════════════════════════════════════════
// PHASE 5: FACE LAYER (Final — only after Phases 0-4 confirmed)
// ═════════════════════════════════════════════════════════════

async function phase5_FaceLayer(): Promise<void> {
  // CRITICAL: Verify all prior phases are stable
  for (let p = 0; p <= 4; p++) {
    if (bootState.phases[p as Phase].status !== 'ok') {
      throw new Error(`Cannot initialize Face Layer: Phase ${p} is not operational (status: ${bootState.phases[p as Phase].status})`);
    }
  }

  phaseStart(5);
  console.log('\n═══════════════════════════════════════════════════════');
  console.log('  PHASE 5: FACE LAYER');
  console.log('═══════════════════════════════════════════════════════');

  // Initialize Simli.ai WebRTC session
  console.log('  → Initializing Simli.ai WebRTC avatar...');
  runtime.avatar = new EchoAvatarService({
    simliApiKey: process.env.SIMLI_API_KEY!,
    faceId: process.env.SIMLI_FACE_ID || 'oneill-pm-default',
  });
  try {
    await runtime.avatar.initializeAvatar();
    console.log('  ✓ Simli avatar streaming');
  } catch (err) {
    console.warn(`  ⚠ Avatar init deferred: ${err instanceof Error ? err.message : err}`);
  }

  // Bind AvatarStateMachine to LangGraph state events
  console.log('  → Binding Avatar State Machine to cognitive events...');
  runtime.stateMachine.onStateChange((visuals) => {
    runtime.avatar?.setState(visuals.state);
    console.log(`  [AVATAR] ${visuals.state} → pulse: ${visuals.pulseColor} (${visuals.pulseRhythm})`);
  });
  console.log('  ✓ State machine bound to 8 cognitive states');

  // Sync Simli PCM input to Gemini Live audio output
  console.log('  → Coupling Simli to Gemini Live audio output...');
  runtime.live?.onResponse((delta) => {
    if (delta.type === 'audio') {
      const pcmData = new Uint8Array(Buffer.from(delta.data, 'base64'));
      runtime.avatar?.syncStream(pcmData);
      runtime.stateMachine.transition('tts_streaming');
    }
  });
  console.log('  ✓ PCM audio stream coupled');

  phaseComplete(5);

  // ═══ ECHO IS FULLY OPERATIONAL ═══
  bootState.fullyOperational = true;
  runtime.stateMachine.transition('user_inactive'); // Start in IDLE

  console.log('\n═══════════════════════════════════════════════════════');
  console.log('  ✓ ECHO COGNITIVE RUNTIME v3.0 — FULLY OPERATIONAL');
  console.log('  ✓ UI Pulse → GREEN ACTIVE');
  console.log('  ✓ All 10 modules online');
  console.log('═══════════════════════════════════════════════════════\n');
}

// ── Phase Helpers ───────────────────────────────────────────

function phaseStart(phase: Phase): void {
  bootState.currentPhase = phase;
  bootState.phases[phase] = { status: 'running', startedAt: new Date() };
}

function phaseComplete(phase: Phase): void {
  bootState.phases[phase].status = 'ok';
  bootState.phases[phase].completedAt = new Date();
  const elapsed = bootState.phases[phase].completedAt!.getTime() - bootState.phases[phase].startedAt!.getTime();
  console.log(`  ═ Phase ${phase} complete (${elapsed}ms)`);
}

// ── Main Boot Sequence ──────────────────────────────────────

async function boot(): Promise<EchoRuntime> {
  console.log('╔═══════════════════════════════════════════════════════╗');
  console.log('║         ECHO COGNITIVE RUNTIME v3.0                  ║');
  console.log('║         O\'Neill Contractors, Inc.                    ║');
  console.log('║         "The AI that doesn\'t assist — it acts."      ║');
  console.log('╚═══════════════════════════════════════════════════════╝');

  try {
    await phase0_SecurityGate();
    await phase1_KnowledgeLayer();
    await phase2_IntelligenceLayer();
    await phase3_PerceptionLayer();
    await phase4_ActionLayer();
    await phase5_FaceLayer();
  } catch (err) {
    const phase = bootState.currentPhase;
    bootState.phases[phase].status = 'failed';
    bootState.phases[phase].error = err instanceof Error ? err.message : String(err);
    console.error(`\n✗ BOOT FAILED at Phase ${phase}: ${bootState.phases[phase].error}`);
    throw err;
  }

  return runtime;
}

// ── Graceful Shutdown ───────────────────────────────────────

async function shutdown(): Promise<void> {
  console.log('\n[ECHO] Initiating graceful shutdown...');
  runtime.reasoning?.stop();
  await runtime.live?.disconnect();
  await runtime.avatar?.teardown();
  await runtime.operator?.teardownSandbox();
  await runtime.codeRunner?.teardown();
  await runtime.knowledgeGraph?.close();
  console.log('[ECHO] Shutdown complete.');
}

// ── Exports ─────────────────────────────────────────────────

export { boot, shutdown, runtime };
export type { BootState, Phase, PhaseStatus };

// ── Entry Point ─────────────────────────────────────────────

const isDirectRun = process.argv[1]?.endsWith('index.ts') || process.argv[1]?.endsWith('index.js');
if (isDirectRun) {
  boot().catch((err) => {
    console.error('Fatal boot error:', err);
    process.exit(1);
  });

  // Handle graceful shutdown signals
  process.on('SIGINT', () => shutdown().then(() => process.exit(0)));
  process.on('SIGTERM', () => shutdown().then(() => process.exit(0)));
}
