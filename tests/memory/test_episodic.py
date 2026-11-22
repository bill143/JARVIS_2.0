"""Tests for EpisodicMemory — long-term facts with confidence/TTL."""

from __future__ import annotations

import time

from openjarvis.core.types import Message, Role
from openjarvis.memory.episodic import EpisodicMemory


def test_store_fact_returns_id():
    memory = EpisodicMemory()
    fact_id = memory.store_fact("the sky is blue", confidence=0.9, source="test")
    assert fact_id in memory.facts
    assert memory.facts[fact_id].fact == "the sky is blue"
    assert memory.facts[fact_id].confidence == 0.9
    assert memory.facts[fact_id].source == "test"


def test_store_fact_without_ttl_never_expires():
    memory = EpisodicMemory()
    fact_id = memory.store_fact("permanent fact", source="test")
    assert memory.facts[fact_id].expires_at is None
    memory.cleanup_expired()
    assert fact_id in memory.facts


def test_cleanup_expired_removes_past_ttl():
    memory = EpisodicMemory()
    fact_id = memory.store_fact("temporary fact", source="test", ttl_hours=1)
    # Force expiry without sleeping in the test.
    memory.facts[fact_id].expires_at = time.time() - 1
    memory.cleanup_expired()
    assert fact_id not in memory.facts


def test_cleanup_expired_keeps_future_ttl():
    memory = EpisodicMemory()
    fact_id = memory.store_fact("future fact", source="test", ttl_hours=1)
    memory.cleanup_expired()
    assert fact_id in memory.facts


def test_retrieve_facts_ranks_by_overlap_and_confidence():
    memory = EpisodicMemory()
    memory.store_fact("the user prefers dark mode", confidence=0.5, source="a")
    memory.store_fact("the user prefers dark mode themes", confidence=0.9, source="b")
    memory.store_fact("completely unrelated statement", confidence=1.0, source="c")

    results = memory.retrieve_facts("what mode does the user prefer", k=5)
    assert len(results) == 2
    assert results[0].source == "b"  # higher overlap * confidence wins
    assert results[1].source == "a"


def test_retrieve_facts_excludes_zero_overlap():
    memory = EpisodicMemory()
    memory.store_fact("apples and oranges", source="a")
    results = memory.retrieve_facts("quantum computing", k=5)
    assert results == []


def test_retrieve_facts_respects_k():
    memory = EpisodicMemory()
    for i in range(5):
        memory.store_fact(f"fact number {i} about cats", source=str(i))
    results = memory.retrieve_facts("cats", k=2)
    assert len(results) == 2


def test_retrieve_facts_purges_expired_first():
    memory = EpisodicMemory()
    fact_id = memory.store_fact("cats are great pets", source="a", ttl_hours=1)
    memory.facts[fact_id].expires_at = time.time() - 1
    results = memory.retrieve_facts("cats", k=5)
    assert results == []
    assert fact_id not in memory.facts


def test_extract_facts_keeps_declarative_sentences():
    memory = EpisodicMemory()
    conversation = [
        Message(role=Role.USER, content="My favorite programming language is Python."),
        Message(role=Role.ASSISTANT, content="That's great to hear about Python."),
    ]
    facts = memory.extract_facts(conversation)
    assert len(facts) == 1
    assert "Python" in facts[0].fact
    assert facts[0].source == "conversation"


def test_extract_facts_skips_questions_and_short_sentences():
    memory = EpisodicMemory()
    conversation = [
        Message(role=Role.USER, content="What time is it? Ok. Sure thing."),
    ]
    facts = memory.extract_facts(conversation)
    assert facts == []


def test_extract_facts_does_not_persist():
    memory = EpisodicMemory()
    conversation = [
        Message(role=Role.USER, content="I live in a small house near the lake."),
    ]
    facts = memory.extract_facts(conversation)
    assert len(facts) == 1
    assert memory.facts == {}
