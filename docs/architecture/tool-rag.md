# Dynamic Tool RAG (Stage 3B)

`openjarvis.tools.tool_indexer` narrows the tools injected into a prompt from
"all registered tools" to just the handful relevant to the current intent. This
is **two-pass tool injection**:

1. **Retrieve** the top *k* (default 3–5) candidate tools by intent.
2. **Inject** only those schemas (as OpenAI function-calling dicts) into the
   prompt.

## Quick start

```python
from openjarvis.tools.tool_indexer import build_index

indexer = build_index(all_tools)            # all_tools: ToolSpec or BaseTool objects

functions = indexer.select_functions("search the web for the latest release notes")
# -> [{"type": "function", "function": {"name": "web_search", ...}}, ...]
# Pass `functions` as the model's tool/function list for this turn.
```

`retrieve()` returns ranked `ScoredTool`s; `selected_names()` returns just the
names; `select_functions()` returns ready-to-inject OpenAI schemas.

## Ranking

The default scorer is a **dependency-free, deterministic** IDF-weighted lexical
matcher over each tool's name, category, description, and parameter names — so
the indexer needs no extra packages and is hermetic in tests. Ties break
alphabetically by tool name for stable output.

Swap in semantic retrieval by supplying a `score_fn`:

```python
from openjarvis.tools.tool_indexer import ToolIndexer

def embedding_score(query_tokens, entry):
    return cosine(embed(" ".join(query_tokens)), embed(entry.text))

indexer = ToolIndexer(score_fn=embedding_score, min_tools=3, max_tools=5)
indexer.index(all_tools)
```

The same seam lets you back retrieval with a `openjarvis.tools.storage` vector
backend for dense/hybrid tool search.

## Result size

- `max_tools` (default 5) is the injection budget — the hard upper bound.
- `top_k` per call overrides `max_tools`.
- `min_tools` (default 3) is a **soft floor**: when at least one tool matches but
  fewer than `min_tools` do, the result is padded with the next tools so the
  agent keeps a minimum viable toolset. A budget smaller than `min_tools` simply
  caps it (no error).
- When **nothing** matches the intent, the first `top_k or max_tools` tools are
  returned in a stable order so the agent still receives a usable set.

## Integration status

Stage 3B ships the indexer as a standalone, fully-tested component (100% line
coverage). Wiring it into the agent loop's tool-assembly step is intentionally
**opt-in** and left to a follow-up to keep this stage low-blast-radius.

## Tests

`tests/tools/test_tool_indexer.py` — 21 unit tests: indexing/dedup, BaseTool
acceptance, relevance ranking, parameter-name matching, budget/`top_k`, floor
padding, no-match fallback, OpenAI schema output, config validation, and custom
scorer injection.
