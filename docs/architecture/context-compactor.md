# Context Compactor (Stage 3A)

`openjarvis.context.compactor` keeps an agent's working context within a model's
usable window. When a conversation grows past a configurable fraction of the
context window (default **75%**), older non-system turns are replaced by a single
structured summary while **system instructions and the most recent turns are
preserved verbatim**.

## Quick start

```python
from openjarvis.context import CompactionConfig, ContextCompactor

compactor = ContextCompactor(
    CompactionConfig(
        context_window_tokens=8192,   # target model's usable window
        trigger_ratio=0.75,           # compact at 75% utilisation
        keep_recent_messages=6,       # trailing turns kept verbatim
    )
)

if compactor.should_compact(conversation):
    conversation = compactor.compact(conversation)   # returns a NEW Conversation
```

`compact()` never mutates its input — it returns a fresh `Conversation` with new
`Message` objects.

## Structured state

Compacted turns are summarised into a `CompactedState` and attached to the
injected summary message's `metadata["compacted_state"]`. `to_dict()` emits the
canonical keys:

```json
{
  "Goal": "...",
  "Progress": "...",
  "Environment": "...",
  "Pending_Steps": ["..."]
}
```

The summary message is a `system` message (matching the existing
`tools/storage/context.py` convention for injected context) tagged with
`metadata = {"compacted": True, "compacted_state": {...}, "replaced_message_count": N}`.
Because it is a system message it is itself preserved by any later compaction and
is never re-summarised.

## Summarisers (pluggable)

The summarisation strategy is a `Summarizer` protocol, so it can be swapped by
config:

- **`HeuristicSummarizer`** (default) — dependency-free and deterministic;
  extracts goal/tools/pending items directly from the messages. Used as the
  fallback so compaction never hard-fails.
- **`LlmSummarizer(engine, model=...)`** — wraps any
  `openjarvis.engine` `InferenceEngine`, requests JSON-mode output, and falls
  back to the heuristic summariser if the engine errors or returns unparsable
  output.

```python
from openjarvis.context import ContextCompactor, CompactionConfig, LlmSummarizer

compactor = ContextCompactor(
    CompactionConfig(context_window_tokens=8192),
    summarizer=LlmSummarizer(engine, model="gemma-2-2b"),
)
```

## Token accounting

Token counts default to a dependency-free chars/4 heuristic
(`default_token_counter`). Supply any `Callable[[str], int]` (for example a real
tokenizer's `encode` length) via `CompactionConfig.token_counter` when exact
accounting matters.

## Security note

The summary of older turns can contain text derived from untrusted user/tool
content, and it is injected as a `system` message. This matches the repository's
existing retrieved-context convention (`tools/storage/context.py`), but callers
handling untrusted input should be aware that summarised content is framed, not
sanitised; downstream prompt-injection defences (`openjarvis.security`) still
apply.

## Integration status

Stage 3A ships the compactor as a standalone, fully-tested component with a clean
API. Wiring it into the agent execution loop is intentionally **opt-in** and left
to a follow-up so this stage stays low-blast-radius; call `should_compact()` /
`compact()` at a turn boundary where the loop assembles its `Conversation`.

## Tests

`tests/context/test_compactor.py` — 36 unit tests, 100% line coverage of the
module (token counting, trigger threshold, system/recent preservation,
immutability, no-op paths, heuristic extraction, injected + LLM summarisers with
fallback).
