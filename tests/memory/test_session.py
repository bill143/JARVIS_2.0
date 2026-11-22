"""Tests for SessionMemory — rolling short-term conversation state."""

from __future__ import annotations

from openjarvis.memory.session import SessionMemory


def test_store_and_retrieve():
    memory = SessionMemory()
    memory.store("hello", "hi there")
    memory.store("how are you", "doing well")

    items = memory.retrieve(k=1)
    assert len(items) == 1
    assert items[0].query == "how are you"
    assert items[0].response == "doing well"


def test_retrieve_returns_oldest_first():
    memory = SessionMemory()
    memory.store("q1", "r1")
    memory.store("q2", "r2")
    memory.store("q3", "r3")

    items = memory.retrieve(k=2)
    assert [i.query for i in items] == ["q2", "q3"]


def test_retrieve_k_zero_or_negative_returns_empty():
    memory = SessionMemory()
    memory.store("q", "r")
    assert memory.retrieve(k=0) == []
    assert memory.retrieve(k=-1) == []


def test_store_returns_unique_ids():
    memory = SessionMemory()
    id1 = memory.store("q1", "r1")
    id2 = memory.store("q2", "r2")
    assert id1 != id2


def test_metadata_is_stored():
    memory = SessionMemory()
    memory.store("q", "r", metadata={"channel": "slack"})
    assert memory.items[0].metadata == {"channel": "slack"}


def test_trims_oldest_when_over_budget():
    # "wordN" tokens: 1 token each side → each turn is 2 tokens.
    memory = SessionMemory(max_tokens=4)
    memory.store("word1", "word1")  # 2 tokens
    memory.store("word2", "word2")  # +2 = 4 tokens, still fits
    memory.store("word3", "word3")  # would be 6 tokens → oldest evicted

    assert len(memory.items) == 2
    assert [i.query for i in memory.items] == ["word2", "word3"]


def test_single_oversized_turn_can_empty_session():
    memory = SessionMemory(max_tokens=2)
    memory.store("one two three", "four five six")  # 6 tokens > budget
    assert memory.items == []


def test_clear_removes_all_items():
    memory = SessionMemory()
    memory.store("q", "r")
    memory.clear()
    assert memory.items == []


def test_summarize_renders_transcript():
    memory = SessionMemory()
    memory.store("hello", "hi there")
    text = memory.summarize()
    assert "User: hello" in text
    assert "Assistant: hi there" in text


def test_summarize_empty_session():
    memory = SessionMemory()
    assert memory.summarize() == ""
