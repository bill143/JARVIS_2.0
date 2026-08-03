"""Short-term and long-term memory primitives for OpenJarvis.

This package holds in-process memory abstractions consumed by
``tools.storage.context.inject_context``:

- :class:`SessionMemory` — rolling short-term conversation state.
- :class:`EpisodicMemory` — long-term facts with confidence/provenance/TTL.

These are distinct from the persistent ``MemoryBackend`` implementations in
``tools.storage`` (SQLite/FAISS/BM25/ColBERT), which back knowledge-base
retrieval rather than conversational state.
"""

from __future__ import annotations

from openjarvis.memory.episodic import EpisodicFact, EpisodicMemory
from openjarvis.memory.session import SessionMemory, SessionMemoryItem

__all__ = [
    "EpisodicFact",
    "EpisodicMemory",
    "SessionMemory",
    "SessionMemoryItem",
]
