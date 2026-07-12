# RFC 0001: Adopt OpenHuman as the Web-of-Brains Memory Layer

- **Status:** Accepted
- **Date:** 2026-07-12
- **Owners:** @bill143
- **Related Repo:** https://github.com/tinyhumansai/openhuman

## 1) Decision

We adopt **OpenHuman** as the primary shared-memory and orchestration layer for our multi-agent
system ("web of brains").

We will **not** add Cognee at this stage — it overlaps OpenHuman's core memory role and increases
architectural complexity. It stays a shelved, swappable option (see §7, adapter principle).

We choose **Option A by default**:
- **A) Query OpenHuman directly** for shared memory and agent coordination (no extra memory stack).

We may optionally add **Option B later**:
- **B) Obsidian mirror/export layer** for human-readable Markdown visibility (read-oriented;
  policy in §8 Phase 3), without replacing OpenHuman as the source of truth.

## 2) Context

Project goal: run multiple AI agents that each have local reasoning but also **share and link**
durable knowledge across the system, with JARVIS as the conductor.

OpenHuman already provides (verified):
- **Memory Tree** — a durable, linked memory graph (compresses docs/emails/chats; ~1B-token scale).
- **A shared memory backend** — `memory.backend = "agentmemory"` lets the same durable store power
  OpenHuman alongside Claude Code, Cursor, Codex, OpenCode — i.e. multiple agents sharing one brain.
- **Agent-to-agent orchestration** (Signal-protocol E2E messaging).

This is exactly the "intertwined agents + shared brain" model we need.

## 3) Problem Statement

We need a practical architecture for (1) multi-agent collaboration, (2) shared/durable/linked
memory, (3) minimal operational complexity, (4) private/local deployment controls. The key risk is
overlapping memory systems that fragment the source of truth and multiply failure modes.

## 4) Options Considered

### Option A — OpenHuman only (selected default)
Use OpenHuman as the memory/orchestration backend; JARVIS queries it through its adapter.

**Pros:** fastest time to value; fewest moving parts; single source of truth; low maintenance.
**Cons:** memory internals are less human-editable than a Markdown vault; depends on OpenHuman's API.

### Option B — OpenHuman + Obsidian mirror (deferred optional)
Keep OpenHuman authoritative; add a thin export/mirror to Markdown for human visibility.

**Pros:** human-readable, inspectable knowledge; better manual-curation workflow for non-engineers.
**Cons:** synchronization complexity; drift risk unless strict source-of-truth + conflict policy.

### Option C — OpenHuman + Cognee (rejected)
Layer Cognee in parallel for memory/knowledge.

**Pros:** niche feature overlap.
**Cons:** redundant brain layers; higher complexity/cost/debugging; ambiguous source of truth.

## 5) Non-Goals

- Building a public agent network.
- Enabling crypto/payment marketplace functionality.
- Replacing OpenHuman memory with an external graph at this stage.

## 6) Security and Scope Constraints (MUST)

For private deployment, the following stay **disabled** unless explicitly re-approved:
- tiny.place federation / public-network features
- x402 / USDC payment flows
- marketplace participation
- wallet-dependent agent-economy capabilities not needed for private ops

**Enforcement responsibility is split (see §7):**
- **OpenHuman's own `config.toml` is the source of truth** — these features are disabled *there*.
- **JARVIS verifies/asserts at startup** — it fetches OpenHuman's `/schema` and raises a prominent
  warning if any network/payment markers appear; JARVIS cannot itself disable OpenHuman features.

**License (MUST):** OpenHuman is **GPL-3.0**. JARVIS integrates with it **only at arm's-length over
HTTP and never embeds or links OpenHuman code.** Arm's-length process interaction keeps JARVIS clear
of GPL copyleft; embedding is prohibited.

## 7) Architecture (Initial)

1. Agents perform task-specific reasoning.
2. Agents read (and, when authorized, write) shared context through the OpenHuman memory backend.
3. JARVIS (conductor) handles routing/orchestration and governance policy.
4. OpenHuman is the system memory **source of truth**.
5. (Optional later) An Obsidian mirror is read-oriented, or controlled-bidirectional with a conflict
   policy.

**Adapter principle (first-class):** All OpenHuman access flows through JARVIS's own adapter — the
`OpenHumanTransport` seam and `OpenHumanClient` (`integrations/.../openhuman/`). Agents never touch
OpenHuman's internals. Because the backend sits behind this seam, it is **swappable** — replacing
OpenHuman with Cognee (or any store) later is a single new transport implementation, with agents and
tools untouched. This is what makes "one brain now, swap if it fails" safe, and it is the concrete
realization of the anti-lock-in mitigation in §10.

**How JARVIS participates:** `memory.backend = "agentmemory"` is how *OpenHuman-family* agents
(Claude Code / Cursor / Codex / OpenCode) share memory natively. **JARVIS is not one of those** — it
is a separate JVM app that participates **through its adapter/API**, not by adopting that backend.

## 8) Implementation Plan

### Phase 1 (Now): OpenHuman-only baseline
> Note: what is currently shipped is **read-only** (`openhuman_status`, `openhuman_schema`,
> `openhuman_memory_search`, `openhuman_consult`). Durable **writes** are added in Task #1 below;
> until then the shared brain is read-only from JARVIS.

- [ ] **Task #1 — gated `openhuman_memory_write`:** a permission-gated (MUTATING risk tier) + fully
      audited durable-write path. **Deny-by-default**: writes are refused until explicitly enabled,
      and only for authorized roles.
- [ ] Confirm OpenHuman's actual RPC method names via `/schema` (client method names are configurable
      pending this).
- [ ] Finalize OpenHuman config for the shared memory backend.
- [ ] Connect active agents to the shared memory (JARVIS via adapter; OpenHuman-native agents via
      `agentmemory`).
- [ ] Define canonical memory namespaces/tags (project, user, task, time).
- [ ] Define retrieval/write conventions per role (§ role policy below).
- [ ] Startup config assertions for tiny.place / x402 / marketplace (verify private mode).
- [ ] Smoke tests for cross-agent memory recall.

**Role policy (write authorization):**
- **Durable writes:** JARVIS conductor + explicitly authorized agents only.
- **Advisors:** read-only (never write).
- **Default:** deny-by-default for all write operations until explicitly enabled.

### Phase 2 (Hardening)
- [ ] Observability: memory write/read logs, latency, failure metrics.
- [ ] Data retention + pruning policy (JARVIS keeps its **audit log of writes indefinite**).
- [ ] Backup/restore of the durable store.
- [ ] Privacy filters / redaction for sensitive content.

### Phase 3 (Optional): Obsidian mirror
- [ ] Export schema to Markdown.
- [ ] Scheduled or event-driven sync (read-oriented first).
- [ ] Conflict policy (OpenHuman authoritative unless exception).
- [ ] Drift detection + repair.

## 9) Acceptance Criteria

1. **Shared memory works** — at least **3 distinct agents** (e.g. JARVIS + two OpenHuman-native
   agents such as Claude Code and Cursor) can read/write shared context and use each other's prior
   outputs.
2. **Single source of truth** — OpenHuman is the only production memory authority; no parallel memory
   brain in production.
3. **Private mode enforced** — public-network / crypto / marketplace features confirmed OFF in
   OpenHuman config, and JARVIS's startup assertion passes clean.
4. **Operational reliability** — cross-agent retrieval success rate and latency meet agreed
   thresholds for 2+ weeks.

## 10) Risks and Mitigations

- **Vendor/framework lock-in** → all memory access sits behind our adapter interface
  (`OpenHumanTransport` / `OpenHumanClient`); backend is swappable (§7).
- **Memory quality degrades (noise/duplication)** → periodic summarization, dedupe, retention.
- **Security leakage from misconfigured public features** → OpenHuman config disables them + JARVIS
  startup assertions + (future) CI policy validation.
- **Team needs human-readable review** → optional Obsidian mirror (Option B / Phase 3).

## 11) Rollback Plan

If OpenHuman fails functional/reliability criteria: (1) freeze new writes from non-critical agents;
(2) export the latest durable-memory snapshot; (3) switch agents to fallback local/session-scoped
memory; (4) reassess alternate backends (Cognee is the shelved candidate — swap via the adapter).

## 12) Open Questions (with current recommendations)

- **Bidirectional Obsidian editing vs read-only mirror?** Start read-only mirror; go bidirectional
  only if manual curation becomes a real need.
- **Retention windows by type?** Defer to OpenHuman's retention for now; keep JARVIS's audit of
  writes indefinite. Revisit in Phase 2.
- **Which roles create durable writes vs ephemeral?** Conductor + authorized agents write durable
  (gated); advisors read-only; everything else ephemeral/session-scoped.
- **Drift thresholds?** N/A until Phase 3 (no second store under Option A, so no drift).

## 13) Final Recommendation

Proceed with **Option A now** (OpenHuman-only shared brain), **shelve Cognee**, revisit **Option B**
only if human-readable visibility becomes a real operational need. Phase 1's first concrete code is
the gated, audited, deny-by-default `openhuman_memory_write` path.
