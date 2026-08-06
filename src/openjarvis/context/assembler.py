"""Context assembler — turns budgeted memory/knowledge into a structured,
cited prompt package.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional, Sequence

from openjarvis.context.budgeter import BudgetAllocation, ContextBudgeter


@dataclass(slots=True)
class KnowledgeChunk:
    """One piece of retrieved knowledge with a citeable source."""

    text: str
    source: str = ""


@dataclass(slots=True)
class ContextPackage:
    """A fully assembled, budget-respecting context package."""

    system_prompt: str
    memory_section: str
    knowledge_section: str
    citations: List[str]
    allocation: BudgetAllocation


class ContextAssembler:
    """Assembles system instructions, memory facts, and knowledge chunks into
    one budget-respecting, citeable context package.

    This composes with — rather than replaces —
    :func:`openjarvis.tools.storage.context.inject_context`, which stays the
    SDK's live request-time path for prepending a single retrieved-knowledge
    block onto an existing message list. ``ContextAssembler`` is for callers
    that need to combine *multiple* sources (system instructions, episodic
    memory, and one or more knowledge sources) under one shared token
    budget when building a context from scratch.
    """

    def __init__(self, budgeter: Optional[ContextBudgeter] = None) -> None:
        self.budgeter = budgeter or ContextBudgeter()

    def assemble(
        self,
        *,
        system_instructions: str,
        memory_facts: Sequence[str] = (),
        knowledge_chunks: Sequence[KnowledgeChunk] = (),
    ) -> ContextPackage:
        """Budget and format the three sources into a ``ContextPackage``."""
        chunk_texts = [c.text for c in knowledge_chunks]
        allocation = self.budgeter.allocate(
            system_instructions, memory_facts, chunk_texts
        )

        # allocation.knowledge_chunks is always an in-order prefix of chunk_texts
        # (the budgeter keeps a greedy prefix), so this slice reproduces the
        # same chunks their KnowledgeChunk (with source) form.
        kept_chunks = list(knowledge_chunks)[: len(allocation.knowledge_chunks)]
        knowledge_section = "\n\n".join(
            f"[Source: {c.source}] {c.text}" if c.source else c.text
            for c in kept_chunks
        )
        memory_section = "\n".join(allocation.memory_facts)
        citations = [c.source for c in kept_chunks if c.source]

        return ContextPackage(
            system_prompt=system_instructions,
            memory_section=memory_section,
            knowledge_section=knowledge_section,
            citations=citations,
            allocation=allocation,
        )


__all__ = ["ContextAssembler", "ContextPackage", "KnowledgeChunk"]
