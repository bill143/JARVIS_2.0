# Inspection: existing memory/context/reranking infrastructure

Written before implementing the Phase 1 baseline (Session Memory, Episodic
Memory, Reranker, Context Budgeter, Context Assembler), because the original
task spec was written without inspecting this codebase and turned out to
overlap substantially with existing, more mature code. This documents what
was found and what changed as a result.

## What already existed (and what that changed)

### Session Memory — already covered, not rebuilt

`openjarvis.sessions.session.SessionStore` (`src/openjarvis/sessions/session.py`)
is a SQLite-backed, cross-channel session store with:
- `get_or_create()` / `save_message()` — persistence keyed by user identity,
  linkable across channels (`link_channel`).
- `consolidate()` — summarizes the oldest half of a session's messages once
  a message-count threshold is crossed.
- `decay()` — age-based session expiry/cleanup.

`openjarvis.sessions.compression` additionally provides four pluggable,
`CompressionRegistry`-registered strategies for trimming a live message list:
`session_consolidation`, `rule_based_precompression`, `model_summarization`,
`tiered_summaries`.

The original spec's "Session Memory" component (`memory/session.py`) was a
from-scratch, in-memory-only dataclass list with its own word-count-based
trimming — strictly less capable than what's already here, and would have
been a second, competing, worse implementation of the same concept.
**Decision (confirmed with the project owner): skip it.** No
`memory/session.py` was written. Any future caller needing rolling
conversation state should use `SessionStore` + a `CompressionRegistry`
strategy directly.

### Reranker — extended, not duplicated

`openjarvis.connectors.retriever` already defines an abstract `Reranker`
base class and a working `ColBERTReranker` implementation, consumed by
`TwoStageRetriever` (BM25 recall + optional semantic rerank). The spec asked
for a *new*, differently-implemented `Reranker` class (BGE-based) at
`knowledge/reranker.py` — creating that would mean two unrelated classes
both named `Reranker` doing the same conceptual job, which is exactly the
kind of confusion a codebase like this should avoid.

**Decision: added `BGEReranker` as a new concrete subclass of the existing
`openjarvis.connectors.retriever.Reranker` ABC**, in a new file
`src/openjarvis/connectors/bge_reranker.py` (not `knowledge/reranker.py` —
there is no `knowledge/` package in this codebase). It follows the same
lazy-load-with-graceful-fallback pattern as `ColBERTReranker`. See
`connectors/RESEARCH_RERANKER.md` for FlagEmbedding usage notes.

### Context injection — additive, not a replacement

`openjarvis.tools.storage.context.inject_context` (+ `ContextConfig`,
`build_context_message`, `format_context`) is **actively wired into the live
request path** in both `sdk.py` and `system.py` (`Jarvis`/`JarvisSystem`
call `self._inject_context(...)` before every completion). It handles one
job well: retrieve from a single `MemoryBackend`, filter by score, truncate
by a word-count approximation, and prepend a system message to an existing
message list.

The spec's "Context Budgeter" and "Context Assembler" ask for something
`inject_context` doesn't do: allocate one shared token budget across
*three* independent sources (system instructions, memory facts, and
knowledge chunks) with an explicit priority order, for a caller building a
context from scratch rather than augmenting a message list. That capability
didn't exist anywhere in the codebase.

**Decision: built `context/budgeter.py` and `context/assembler.py` as new,
additive modules. `tools/storage/context.py` was not modified** — it's a
live, in-use code path and changing its behavior or signature was out of
scope for this baseline. `ContextAssembler`'s docstring notes explicitly
that it composes with, rather than replaces, `inject_context`.

One real inconsistency remains, noted rather than silently fixed:
`inject_context`'s token counting (`_count_tokens` in
`tools/storage/context.py`) is still a whitespace-split approximation, while
the new `context.budgeter.count_tokens` uses `tiktoken`. Unifying these is a
reasonable follow-up but touches a live request path and wasn't attempted
here.

### Episodic Memory — genuinely new

No existing implementation was found anywhere in the codebase (`grep -ril
episodic` returned nothing). Built as specified, as a new `memory/episodic.py`
module. `extract_facts()` is kept as a stub per the task's own guidance —
real fact extraction (entity/relation extraction, dedup, contradiction
resolution) is a project of its own.

## Other codebase conventions followed

- `@dataclass(slots=True)` for value types, matching `RetrievalResult`,
  `SessionMessage`, etc.
- `from __future__ import annotations` at the top of every module.
- Lazy `try/except ImportError`-guarded imports for optional heavy
  dependencies (mirrors `tools/storage/__init__.py` and `ColBERTReranker`),
  with a warning logged once rather than on every call.
- The top-level `src/openjarvis/__init__.py` only exports the SDK facade
  (`Jarvis`, `JarvisSystem`, `MemoryHandle`, `SystemBuilder`) — it does not
  flatten every submodule's classes into the package root. New code follows
  this: `memory/__init__.py` and `context/__init__.py` each export their own
  classes, and neither is re-exported from the top-level `__init__.py`. This
  deviates from the original spec's instruction to "update
  `/src/openjarvis/__init__.py` to expose new classes" — that instruction
  didn't match the codebase's actual convention.
