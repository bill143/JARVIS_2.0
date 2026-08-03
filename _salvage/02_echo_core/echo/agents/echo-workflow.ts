/**
 * MODULE 2 — THE REASONING ENGINE
 * Stateful Infinite Thinking Architecture
 *
 * Framework: LangGraph — TypeScript
 * Memory: MemorySaver (LangGraph built-in checkpointing)
 * Execution: Cyclic directed graph with interrupt-capable nodes
 * Background cycle: 30s polling interval (configurable)
 */

import { StateGraph, MemorySaver, Annotation, END } from '@langchain/langgraph';

// ── State Schema ────────────────────────────────────────────

export const EchoState = Annotation.Root({
  messages: Annotation<Array<{ role: string; content: string }>>({
    reducer: (prev, next) => [...prev, ...next],
    default: () => [],
  }),
  currentSignal: Annotation<string | null>({
    reducer: (_prev, next) => next,
    default: () => null,
  }),
  reasoningRoute: Annotation<'vault_query' | 'vision_ocr' | 'direct' | 'world_feed' | 'code_exec' | null>({
    reducer: (_prev, next) => next,
    default: () => null,
  }),
  vaultContext: Annotation<string[]>({
    reducer: (_prev, next) => next,
    default: () => [],
  }),
  outputReady: Annotation<boolean>({
    reducer: (_prev, next) => next,
    default: () => false,
  }),
  reflexionScore: Annotation<'PASS' | 'WARN' | 'FAIL' | null>({
    reducer: (_prev, next) => next,
    default: () => null,
  }),
});

export type EchoStateType = typeof EchoState.State;

// ── Node Implementations ────────────────────────────────────

/** PERCEIVE — ingests new signals from UI events, Vault webhooks, or user speech */
async function perceiveNode(state: EchoStateType): Promise<Partial<EchoStateType>> {
  const signal = state.currentSignal;
  if (!signal) return { reasoningRoute: null };

  // Route classification logic
  let route: EchoStateType['reasoningRoute'] = 'direct';
  const lowerSignal = signal.toLowerCase();

  if (lowerSignal.includes('document') || lowerSignal.includes('spec') || lowerSignal.includes('estimate') || lowerSignal.includes('submittal')) {
    route = 'vault_query';
  } else if (lowerSignal.includes('image') || lowerSignal.includes('photo') || lowerSignal.includes('blueprint')) {
    route = 'vision_ocr';
  } else if (lowerSignal.includes('price') || lowerSignal.includes('weather') || lowerSignal.includes('sam.gov') || lowerSignal.includes('regulation')) {
    route = 'world_feed';
  } else if (lowerSignal.includes('calculate') || lowerSignal.includes('compute') || lowerSignal.includes('run') || lowerSignal.includes('script')) {
    route = 'code_exec';
  }

  return { reasoningRoute: route };
}

/** REASON — routes to appropriate sub-chain */
async function reasonNode(state: EchoStateType): Promise<Partial<EchoStateType>> {
  const route = state.reasoningRoute;

  switch (route) {
    case 'vault_query':
      // Vault retrieval will be injected by echo-vault.ts
      return { outputReady: true };
    case 'vision_ocr':
      // Vision processing delegated to echo-vision.ts
      return { outputReady: true };
    case 'world_feed':
      // External data via echo-world-feed.ts
      return { outputReady: true };
    case 'code_exec':
      // Sandboxed execution via echo-code-runner.ts
      return { outputReady: true };
    case 'direct':
    default:
      return { outputReady: true };
  }
}

/** SURFACE — formats and emits response to ECHO's output layer */
async function surfaceNode(state: EchoStateType): Promise<Partial<EchoStateType>> {
  // Emit the formatted response — actual rendering handled by UI layer
  return {
    outputReady: false,
    currentSignal: null,
  };
}

/** Conditional edge router */
function routeAfterPerceive(state: EchoStateType): string {
  if (!state.reasoningRoute) return END;
  return 'reason';
}

function routeAfterReason(state: EchoStateType): string {
  if (state.outputReady) return 'surface';
  return END;
}

// ── Graph Builder ───────────────────────────────────────────

export function buildEchoWorkflow() {
  const checkpointer = new MemorySaver();

  const graph = new StateGraph(EchoState)
    .addNode('perceive', perceiveNode)
    .addNode('reason', reasonNode)
    .addNode('surface', surfaceNode)
    .addEdge('__start__', 'perceive')
    .addConditionalEdges('perceive', routeAfterPerceive, ['reason', '__end__'])
    .addConditionalEdges('reason', routeAfterReason, ['surface', '__end__'])
    .addEdge('surface', '__end__');

  return graph.compile({ checkpointer });
}

// ── Background Polling ──────────────────────────────────────

export class EchoReasoningLoop {
  private intervalMs: number;
  private timer: ReturnType<typeof setInterval> | null = null;
  private workflow: ReturnType<typeof buildEchoWorkflow>;

  constructor(intervalMs: number = 30000) {
    this.intervalMs = intervalMs;
    this.workflow = buildEchoWorkflow();
  }

  /** Start background polling cycle */
  start(threadId: string): void {
    this.timer = setInterval(async () => {
      try {
        await this.workflow.invoke(
          { currentSignal: '__background_poll__' },
          { configurable: { thread_id: threadId } },
        );
      } catch (err) {
        console.error('[ECHO-WORKFLOW] Background cycle error:', err);
      }
    }, this.intervalMs);
  }

  /** Process a direct user signal */
  async processSignal(signal: string, threadId: string): Promise<EchoStateType> {
    return this.workflow.invoke(
      { currentSignal: signal, messages: [{ role: 'user', content: signal }] },
      { configurable: { thread_id: threadId } },
    );
  }

  /** Stop background polling */
  stop(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}
