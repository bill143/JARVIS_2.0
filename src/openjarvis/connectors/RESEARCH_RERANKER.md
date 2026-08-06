# Research: BGE reranker (FlagEmbedding)

## Why this file lives here, not under `knowledge/`

The task spec asked for `/src/openjarvis/knowledge/reranker.py` and
`/src/openjarvis/knowledge/RESEARCH_RERANKER.md`. There is no `knowledge/`
package anywhere in this codebase, and a working `Reranker` ABC +
`ColBERTReranker` implementation already exists in
`openjarvis.connectors.retriever`. The new BGE-based reranker was added as
a second concrete subclass of that *existing* ABC, in
`connectors/bge_reranker.py`, instead of introducing a competing `Reranker`
class under a new package. See `memory/INSPECTION.md` for the full
reasoning.

## FlagEmbedding / bge-reranker

- **Package**: `pip install FlagEmbedding` (GitHub: `FlagOpen/FlagEmbedding`,
  formerly referenced as `FlagEmbedding/BGE`).
- **Class**: `FlagReranker` (not `BGEreranker`, which doesn't exist in the
  package — this was a hallucinated name in the original task spec, caught
  and corrected during implementation).
- **Basic usage**:
  ```python
  from FlagEmbedding import FlagReranker

  reranker = FlagReranker("BAAI/bge-reranker-base", use_fp16=True)
  scores = reranker.compute_score([["query", "passage 1"], ["query", "passage 2"]])
  ```
  `compute_score` accepts a list of `[query, passage]` pairs and returns a
  list of floats (or a single float if given one pair — handled explicitly
  in `BGEReranker.rerank`). `normalize=True` maps raw logits into a
  `[0, 1]` range via sigmoid, which is what `BGEReranker` uses so scores are
  comparable across calls.
- **Model choices**: `BAAI/bge-reranker-base` (default here — good
  quality/speed tradeoff), `BAAI/bge-reranker-large` (better quality,
  slower), `BAAI/bge-reranker-v2-m3` (multilingual, larger).
- **Cost/latency shape**: unlike ColBERT's late-interaction scoring (which
  can reuse cached per-document token embeddings via `EmbeddingStore`), a
  cross-encoder like BGE's reranker scores every `(query, candidate)` pair
  fresh — there's no meaningful per-document cache to build, since the
  score depends on the full cross-attention between query and candidate
  together, not on independently-encoded document embeddings. This is
  simpler to reason about and integrate (no cache plumbing needed) but
  means cost scales linearly with `top_k` candidates on every call, with no
  reuse across queries.
- **`use_fp16`**: only actually reduces memory/latency on a CUDA GPU;
  harmless but ineffective on CPU-only inference, which is why it's exposed
  as a constructor parameter rather than hardcoded.

## Failure mode handled

Same shape as `ColBERTReranker`: if `FlagEmbedding` isn't installed (it's
an optional extra, `memory-reranker`, not a core dependency — see
`pyproject.toml`), `BGEReranker.rerank()` logs one warning and returns the
incoming candidate order, truncated to `top_k`, rather than raising.
