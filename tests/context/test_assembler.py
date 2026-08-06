"""Tests for the context assembler."""

from __future__ import annotations

from openjarvis.context.assembler import ContextAssembler, KnowledgeChunk
from openjarvis.context.budgeter import ContextBudgeter


def test_assemble_builds_citations_from_sourced_chunks():
    assembler = ContextAssembler()

    package = assembler.assemble(
        system_instructions="You are a helper.",
        memory_facts=["the user prefers metric units"],
        knowledge_chunks=[
            KnowledgeChunk(text="chunk one", source="doc-1"),
            KnowledgeChunk(text="chunk two", source="doc-2"),
        ],
    )

    assert package.system_prompt == "You are a helper."
    assert "the user prefers metric units" in package.memory_section
    assert package.citations == ["doc-1", "doc-2"]
    assert "[Source: doc-1] chunk one" in package.knowledge_section


def test_assemble_omits_citation_for_unsourced_chunks():
    assembler = ContextAssembler()

    package = assembler.assemble(
        system_instructions="You are a helper.",
        knowledge_chunks=[KnowledgeChunk(text="anonymous chunk")],
    )

    assert package.citations == []
    assert package.knowledge_section == "anonymous chunk"


def test_assemble_respects_budget_truncation():
    budgeter = ContextBudgeter(max_tokens=10)
    assembler = ContextAssembler(budgeter=budgeter)

    package = assembler.assemble(
        system_instructions="short system prompt",
        knowledge_chunks=[
            KnowledgeChunk(text="word " * 200, source="huge-doc"),
        ],
    )

    assert package.allocation.truncated is True
    assert package.citations == []
    assert package.knowledge_section == ""


def test_assemble_with_no_memory_or_knowledge():
    assembler = ContextAssembler()

    package = assembler.assemble(system_instructions="You are a helper.")

    assert package.memory_section == ""
    assert package.knowledge_section == ""
    assert package.citations == []
