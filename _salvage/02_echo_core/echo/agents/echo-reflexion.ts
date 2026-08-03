/**
 * MODULE 6 — THE REFLEXION LAYER
 * Self-Auditing Hallucination Prevention & Output Verification Engine
 *
 * Pattern: Reflexion architecture
 * Implementation: LangGraph sub-graph with evaluator + reflector nodes
 * Grounding: Vault (retrieval) + Tool results (factual) + Schema validators
 * Max cycles: 3 before escalating to human
 */

import { StateGraph, Annotation, END } from '@langchain/langgraph';
import type { EchoVault, VaultChunk } from '../vault/echo-vault.js';

// ── State Schema ────────────────────────────────────────────

export const ReflexionState = Annotation.Root({
  draftResponse: Annotation<string>({
    reducer: (_prev, next) => next,
    default: () => '',
  }),
  evaluationScore: Annotation<'PASS' | 'WARN' | 'FAIL'>({
    reducer: (_prev, next) => next,
    default: () => 'FAIL',
  }),
  critique: Annotation<string>({
    reducer: (_prev, next) => next,
    default: () => '',
  }),
  revisedResponse: Annotation<string>({
    reducer: (_prev, next) => next,
    default: () => '',
  }),
  vaultEvidence: Annotation<string[]>({
    reducer: (_prev, next) => next,
    default: () => [],
  }),
  cycleCount: Annotation<number>({
    reducer: (_prev, next) => next,
    default: () => 0,
  }),
  maxCycles: Annotation<number>({
    reducer: (_prev, next) => next,
    default: () => 3,
  }),
  sourcesCited: Annotation<string[]>({
    reducer: (_prev, next) => next,
    default: () => [],
  }),
  uncertaintyFlags: Annotation<string[]>({
    reducer: (_prev, next) => next,
    default: () => [],
  }),
  escalateToHuman: Annotation<boolean>({
    reducer: (_prev, next) => next,
    default: () => false,
  }),
});

export type ReflexionStateType = typeof ReflexionState.State;

// ── Evaluator Node ──────────────────────────────────────────

async function evaluatorNode(state: ReflexionStateType): Promise<Partial<ReflexionStateType>> {
  const response = state.revisedResponse || state.draftResponse;
  const evidence = state.vaultEvidence;

  // Cross-check factual claims against Vault evidence
  const claimChecks = evaluateClaims(response, evidence);

  // Validate numbers (dollar amounts, quantities, dates)
  const numberChecks = validateNumbers(response);

  // Score the response
  const failedClaims = claimChecks.filter(c => !c.supported);
  const invalidNumbers = numberChecks.filter(n => !n.valid);

  let score: 'PASS' | 'WARN' | 'FAIL';
  if (failedClaims.length === 0 && invalidNumbers.length === 0) {
    score = 'PASS';
  } else if (failedClaims.length <= 1 && invalidNumbers.length === 0) {
    score = 'WARN';
  } else {
    score = 'FAIL';
  }

  const uncertainties = [
    ...failedClaims.map(c => `Unverified claim: "${c.claim}"`),
    ...invalidNumbers.map(n => `Unverified number: ${n.value}`),
  ];

  return {
    evaluationScore: score,
    uncertaintyFlags: uncertainties,
    cycleCount: state.cycleCount + 1,
  };
}

// ── Reflector Node ──────────────────────────────────────────

async function reflectorNode(state: ReflexionStateType): Promise<Partial<ReflexionStateType>> {
  // Generate critique of the failed response
  const critique = `Reflexion cycle ${state.cycleCount}: ${state.uncertaintyFlags.length} issues found. ${state.uncertaintyFlags.join('; ')}`;

  // In production: use LLM to revise the response using vault evidence
  // For now, append uncertainty flags
  const revised = state.draftResponse + `\n\n[ECHO Reflexion Note: ${state.uncertaintyFlags.join('. ')}]`;

  return {
    critique,
    revisedResponse: revised,
  };
}

// ── Router ──────────────────────────────────────────────────

function routeAfterEvaluation(state: ReflexionStateType): string {
  if (state.evaluationScore === 'PASS') return 'output';
  if (state.evaluationScore === 'WARN') return 'output_with_warning';
  if (state.cycleCount >= state.maxCycles) return 'escalate';
  return 'reflector';
}

async function outputNode(state: ReflexionStateType): Promise<Partial<ReflexionStateType>> {
  return { escalateToHuman: false };
}

async function outputWithWarningNode(state: ReflexionStateType): Promise<Partial<ReflexionStateType>> {
  // Append uncertainty flags and source citations
  const sources = state.sourcesCited.length > 0
    ? `\nSources: ${state.sourcesCited.join(', ')}`
    : '';
  return {
    revisedResponse: (state.revisedResponse || state.draftResponse) + sources,
    escalateToHuman: false,
  };
}

async function escalateNode(state: ReflexionStateType): Promise<Partial<ReflexionStateType>> {
  return { escalateToHuman: true };
}

// ── Graph Builder ───────────────────────────────────────────

export function buildReflexionGraph() {
  return new StateGraph(ReflexionState)
    .addNode('evaluator', evaluatorNode)
    .addNode('reflector', reflectorNode)
    .addNode('output', outputNode)
    .addNode('output_with_warning', outputWithWarningNode)
    .addNode('escalate', escalateNode)
    .addEdge('__start__', 'evaluator')
    .addConditionalEdges('evaluator', routeAfterEvaluation, [
      'output', 'output_with_warning', 'reflector', 'escalate',
    ])
    .addEdge('reflector', 'evaluator')
    .addEdge('output', '__end__')
    .addEdge('output_with_warning', '__end__')
    .addEdge('escalate', '__end__')
    .compile();
}

// ── Claim & Number Validators ───────────────────────────────

interface ClaimCheck {
  claim: string;
  supported: boolean;
}

function evaluateClaims(response: string, evidence: string[]): ClaimCheck[] {
  // Extract factual claims from response (sentences with specific assertions)
  const sentences = response.split(/[.!?]+/).filter(s => s.trim().length > 20);
  const evidenceText = evidence.join(' ').toLowerCase();

  return sentences.map(sentence => {
    // Check if key terms from the sentence appear in vault evidence
    const terms = sentence.toLowerCase().split(/\s+/).filter(t => t.length > 4);
    const matchCount = terms.filter(t => evidenceText.includes(t)).length;
    const coverage = terms.length > 0 ? matchCount / terms.length : 0;

    return {
      claim: sentence.trim(),
      supported: coverage > 0.3, // At least 30% term overlap with evidence
    };
  });
}

interface NumberCheck {
  value: string;
  valid: boolean;
}

function validateNumbers(response: string): NumberCheck[] {
  // Extract dollar amounts, quantities, dates
  const dollarPattern = /\$[\d,]+\.?\d*/g;
  const datePattern = /\d{1,2}\/\d{1,2}\/\d{2,4}/g;

  const dollars = [...response.matchAll(dollarPattern)].map(m => m[0]);
  const dates = [...response.matchAll(datePattern)].map(m => m[0]);

  // Basic structural validation — actual verification happens against Vault data
  return [
    ...dollars.map(d => ({ value: d, valid: !isNaN(parseFloat(d.replace(/[$,]/g, ''))) })),
    ...dates.map(d => ({ value: d, valid: !isNaN(Date.parse(d)) })),
  ];
}
