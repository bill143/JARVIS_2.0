# Research: episodic memory & fact extraction

## Sliding-window / consolidation approaches (for context — already implemented here)

This codebase already implements the standard rolling-conversation-state
patterns via `sessions/compression.py`'s `CompressionRegistry` strategies:

- **Recency window** — keep the last N turns verbatim (baseline; not a
  named strategy here, it's what "no compression" means).
- **Summarize-oldest-half** (`SessionConsolidation`) — the classic approach:
  once a session exceeds a threshold, summarize the older portion into one
  system message and keep the recent portion verbatim. Simple, cheap, and
  what `SessionStore.consolidate()` also does independently at the
  persistence layer.
- **Tiered/progressive summarization** (`TieredSummaries`) — L0 (full
  recent) → L1 (paragraph-per-message, older) → L2 (one-line, oldest).
  Reduces information loss versus a single hard cutoff by degrading
  gradually rather than in one step.
- **Rule-based precompression** (`RuleBasedPrecompression`) — no model
  call; truncates oversized tool outputs. Cheap first pass before any of
  the above.

Nothing further was needed here since Session Memory itself was out of
scope for this phase (see `INSPECTION.md`).

## Fact extraction approaches (for `EpisodicMemory.extract_facts`, future work)

Kept as a stub in this phase; options for a follow-up phase, roughly in
order of implementation cost:

1. **LLM-based extraction with structured output** — prompt a model to
   emit a JSON list of `{fact, confidence, entities}` given a conversation
   window. Simplest to get working, most flexible, but adds a model call
   per extraction pass and needs prompt-injection-aware handling (this
   codebase already has `nexus_ai`-style guardrail patterns elsewhere worth
   reusing rather than inventing new ones).
2. **spaCy NER + rule-based relation extraction** — fast, local, no extra
   model call, but brittle for anything beyond named-entity facts
   ("works at X", "lives in Y") and requires a spaCy model download
   (`en_core_web_sm` or larger for better recall).
3. **transformers-based relation extraction** (e.g. REBEL or similar
   seq2seq relation-extraction models) — a middle ground: local, no API
   call, better recall than spaCy's rule-based relations, but a heavier
   dependency and slower per-message than spaCy.

Whichever is chosen, it needs to solve three problems the current stub
deliberately leaves open, and which are genuinely hard enough to deserve
their own phase rather than a rushed placeholder:

- **Deduplication** against already-stored facts (exact match is trivial;
  near-duplicate/paraphrase detection is not).
- **Contradiction resolution** — what happens when a new fact conflicts
  with a stored one (supersede, flag for review, keep both with different
  confidence)?
- **Confidence calibration** — an LLM asked to self-report confidence tends
  to be overconfident; a real system likely wants confidence derived from
  something more grounded (source reliability, corroboration count,
  extraction-method reliability) rather than the extractor's own say-so.
