"""Tests for the context budgeter."""

from __future__ import annotations

from openjarvis.context.budgeter import ContextBudgeter, count_tokens


def test_hard_ceiling_never_exceeded_when_content_overflows():
    budgeter = ContextBudgeter(max_tokens=20)
    system = "you are a helpful assistant"
    memory_facts = [f"fact number {i} with some extra words" for i in range(10)]
    knowledge_chunks = [f"knowledge chunk {i} with more extra words" for i in range(10)]

    allocation = budgeter.allocate(system, memory_facts, knowledge_chunks)

    total = (
        allocation.system_tokens
        + allocation.memory_tokens
        + allocation.knowledge_tokens
    )
    assert total <= budgeter.max_tokens
    assert allocation.truncated is True


def test_no_truncation_when_content_fits():
    budgeter = ContextBudgeter(max_tokens=1000)
    system = "you are a helpful assistant"
    memory_facts = ["fact one", "fact two"]
    knowledge_chunks = ["chunk one"]

    allocation = budgeter.allocate(system, memory_facts, knowledge_chunks)

    assert allocation.truncated is False
    assert allocation.memory_facts == memory_facts
    assert allocation.knowledge_chunks == knowledge_chunks


def test_knowledge_trimmed_before_memory():
    system = "you are a helpful assistant"
    budgeter = ContextBudgeter(max_tokens=count_tokens(system) + 2)
    memory_facts = ["ok"]
    knowledge_chunks = [
        "this knowledge chunk is much too long to fit in the remaining budget"
    ]

    allocation = budgeter.allocate(system, memory_facts, knowledge_chunks)

    assert allocation.memory_facts == ["ok"]
    assert allocation.knowledge_chunks == []


def test_system_instructions_are_never_trimmed():
    long_system = "word " * 500
    budgeter = ContextBudgeter(max_tokens=10)

    allocation = budgeter.allocate(long_system, [], [])

    assert allocation.system_tokens == count_tokens(long_system)
    assert allocation.memory_facts == []
    assert allocation.knowledge_chunks == []


def test_count_tokens_nonzero_for_nonempty_text():
    assert count_tokens("hello world") > 0
    assert count_tokens("") == 0
