"""End-to-end: episodic memory -> context assembly."""

from __future__ import annotations

from openjarvis.context.assembler import ContextAssembler, KnowledgeChunk
from openjarvis.memory.episodic import EpisodicMemory


def test_episodic_memory_to_assembled_context():
    episodic = EpisodicMemory()
    episodic.store_fact("the user's org uses UEI number ABC123", source="onboarding")
    episodic.store_fact("the user prefers concise responses", source="onboarding")

    facts = episodic.retrieve_facts()
    assembler = ContextAssembler()

    package = assembler.assemble(
        system_instructions="You are a helpful construction-estimating assistant.",
        memory_facts=[f.fact for f in facts],
        knowledge_chunks=[
            KnowledgeChunk(
                text="CSI Division 03 covers concrete.",
                source="csi-masterformat",
            ),
        ],
    )

    assert package.system_prompt.startswith("You are a helpful")
    assert "UEI number ABC123" in package.memory_section
    assert "concise responses" in package.memory_section
    assert package.citations == ["csi-masterformat"]
    assert package.allocation.truncated is False
