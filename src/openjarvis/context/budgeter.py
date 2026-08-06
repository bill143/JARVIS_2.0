"""Context budgeter — token-aware allocation across system/memory/knowledge.

Distinct from :mod:`openjarvis.tools.storage.context`, which injects a
*single* retrieved-knowledge block into an existing message list at request
time. ``ContextBudgeter`` allocates a hard token ceiling across three
independent sources — system instructions, memory facts, and knowledge
chunks — for callers building a context from scratch rather than augmenting
one.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import List, Sequence

logger = logging.getLogger(__name__)

try:
    import tiktoken

    _ENCODING = tiktoken.get_encoding("cl100k_base")
except Exception:
    _ENCODING = None

_warned_fallback = False


def count_tokens(text: str) -> int:
    """Count tokens in *text* using tiktoken's ``cl100k_base`` encoding.

    Falls back to a whitespace-split approximation (logged once) if
    ``tiktoken`` is not installed — install the ``context-tiktoken`` extra
    for accurate counts.
    """
    global _warned_fallback
    if _ENCODING is not None:
        return len(_ENCODING.encode(text))
    if not _warned_fallback:
        logger.warning(
            "tiktoken not installed; falling back to whitespace-split token"
            " approximation. Install the 'context-tiktoken' extra for accurate counts."
        )
        _warned_fallback = True
    return len(text.split())


@dataclass(slots=True)
class BudgetAllocation:
    """How the token budget was split, and what survived truncation."""

    system_tokens: int
    memory_tokens: int
    knowledge_tokens: int
    memory_facts: List[str]
    knowledge_chunks: List[str]
    truncated: bool


class ContextBudgeter:
    """Allocates a hard token ceiling across system/memory/knowledge context.

    Priority when combined content exceeds ``max_tokens``: system
    instructions are never trimmed, memory facts are trimmed next, and
    knowledge chunks are trimmed first/hardest — "what the model must
    always know" and "what it was just told" matter more under pressure
    than "what it might find useful" from retrieval. Both ``memory_facts``
    and ``knowledge_chunks`` are kept as an in-order prefix (greedy,
    highest-priority items first) rather than re-sorted by size.

    Note: system instructions are trusted to fit under ``max_tokens`` on
    their own. If they don't, the ceiling is exceeded rather than a
    system prompt being silently cut mid-sentence.
    """

    def __init__(self, max_tokens: int = 32768) -> None:
        self.max_tokens = max_tokens

    def allocate(
        self,
        system_instructions: str,
        memory_facts: Sequence[str],
        knowledge_chunks: Sequence[str],
    ) -> BudgetAllocation:
        """Split ``max_tokens`` across the three sources, in priority order."""
        system_tokens = count_tokens(system_instructions)
        remaining = max(self.max_tokens - system_tokens, 0)

        kept_memory: List[str] = []
        memory_tokens = 0
        for fact in memory_facts:
            t = count_tokens(fact)
            if memory_tokens + t > remaining:
                break
            kept_memory.append(fact)
            memory_tokens += t
        remaining -= memory_tokens

        kept_knowledge: List[str] = []
        knowledge_tokens = 0
        for chunk in knowledge_chunks:
            t = count_tokens(chunk)
            if knowledge_tokens + t > remaining:
                break
            kept_knowledge.append(chunk)
            knowledge_tokens += t

        truncated = len(kept_memory) < len(memory_facts) or len(kept_knowledge) < len(
            knowledge_chunks
        )

        return BudgetAllocation(
            system_tokens=system_tokens,
            memory_tokens=memory_tokens,
            knowledge_tokens=knowledge_tokens,
            memory_facts=kept_memory,
            knowledge_chunks=kept_knowledge,
            truncated=truncated,
        )


__all__ = ["BudgetAllocation", "ContextBudgeter", "count_tokens"]
