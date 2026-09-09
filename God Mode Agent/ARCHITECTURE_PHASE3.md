# JARVIS God Mode Agent — Phase 3 Architecture

Phase 3 adds intelligence scaling, advanced orchestration, enterprise operations,
and compliance readiness on top of Phase 1 (multimodal agent) and Phase 2
(security/reliability/governance). All Phase 1/2 behavior is preserved.

## New packages

```
packages/
  planner/       jarvis_planner   — goal→DAG decomposition, WorkflowStore, WorkflowEngine
                                     (modes: direct, plan-and-execute, reflect-and-revise,
                                      human-approval-gated), checkpoints + resume + abort
  agents/        jarvis_agents    — MessageBus (persistent trace) + MultiAgentOrchestrator
                                     (Coordinator, Researcher, Coder, Verifier, Memory Steward),
                                     bounded debate, arbitration (critic-override / majority),
                                     single-agent fallback
  rag/           jarvis_rag       — hybrid retrieval (vector + BM25 via RRF), reranker
                                     abstraction, multi-query expansion, freshness scoring,
                                     citations + per-segment confidence; ingestion pipeline
                                     (chunking, dedup, versioning, metadata, reindex)
  routing/       jarvis_routing   — ModelRouter (task/latency/risk/budget), fallback matrix,
                                     CostLedger, ResponseCache (exact+semantic), BudgetManager
  compliance/    jarvis_compliance— RetentionManager, KMS abstraction (local + cloud-ready),
                                     EvidenceExporter (JSON/CSV + audit validation), ComplianceModes
  evals/         jarvis_evals     — 7 scenario suites, EvalRunner with persisted runs/scores,
                                     quality gates + regression tolerance + history
  memory/governance.py            — governed memory: confidence, provenance, TTL/decay, pin,
                                     conflict detection/resolution, view/edit/delete/export
```

## Data model (migrations m0008–m0014)

| Migration | Tables | Purpose |
|---|---|---|
| m0008 workflows | `workflows`, `workflow_steps`, `workflow_checkpoints` | planner DAG persistence + replay |
| m0009 agent_messages | `agent_sessions`, `agent_messages` | auditable multi-agent trace |
| m0010 rag | `rag_documents`, `rag_chunks` | versioned, deduped, metadata-tagged knowledge |
| m0011 memory_governance | `memory_items`, `memory_conflicts` | governed memory + conflicts |
| m0012 routing_budgets | `routing_usage`, `tenant_budgets`, `response_cache` | cost accounting + budgets + cache |
| m0013 compliance | `retention_policies`, `evidence_exports` | retention + evidence ledger |
| m0014 approvals2_evals | `approval_stages`, `eval_runs`, `eval_scores` | multi-stage approvals + eval history |

## Request flows

- **Workflow**: `POST /workflows` decomposes the goal into a DAG and persists it →
  `/start` executes step-by-step honoring dependencies, retrying and checkpointing
  each step; `human-approval-gated` mode pauses before high-risk steps and
  `/resume` continues after approval; `/abort` cooperatively stops.
- **Multi-agent**: `POST /agents/run` runs the coordinator over bounded debate
  rounds; researcher grounds via the RAG retriever, coder runs tools, verifier
  critiques, memory steward recommends. Arbitration decides; every message is
  persisted for `/agents/sessions/{id}` introspection. Any specialist error falls
  back to single-agent mode.
- **RAG**: ingestion chunks + embeds + indexes (dedup by content hash, version by
  source); retrieval expands the query, fuses vector + BM25 ranks (RRF), reranks,
  scores freshness, and returns cited results with confidence.
- **Routing/cost**: the router classifies the task and picks a provider/model from
  the fallback matrix, honoring latency target, per-tenant daily budget, and
  compliance approved-providers. Every model call is metered in the cost ledger;
  budgets alert at 80% and force the mock tier when exceeded.
- **Compliance**: retention policies per data class; toggles for compliance /
  audit-strict / restricted-tool / export-control modes; evidence export bundles
  the hash-chained audit log with a tamper-evidence verification and SHA-256 digest.
- **Evals**: 7 suites score real subsystem behavior; a run passes only if it meets
  the threshold and does not regress beyond tolerance vs the persisted baseline.

## Observability 2.0

Metrics added: `jarvis_workflow_steps_total`, `jarvis_rag_queries_total`,
`jarvis_rag_hit_rate`, `jarvis_rag_rerank_lift_total`, `jarvis_rag_citation_coverage`,
plus per-tenant cost via the ledger. Endpoints: `GET /metrics` (Prometheus),
`GET /observability/summary` (breakers/queue/audit), `GET /observability/slo`
(p-latency, tool success rate, cost totals, RAG hit rate). Trace spans cover
agent handoffs, planner states, approval waits, and retries/fallbacks.

## Design constraints honored

- **Deterministic + offline**: reranker, KMS, cross-encoder, and providers all have
  deterministic fallbacks; the entire stack runs with no external services.
- **Windows-first**: pathlib everywhere; SQLite with `busy_timeout`; PowerShell scripts.
- **Backward compatible**: Phase 1/2 endpoints, tests, and data are unchanged;
  Phase 3 only adds tables and routes.
