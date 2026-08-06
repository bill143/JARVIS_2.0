"""Tests for episodic memory."""

from __future__ import annotations

import time

from openjarvis.memory.episodic import EpisodicMemory


def test_store_and_retrieve_fact():
    memory = EpisodicMemory()
    memory.store_fact("the sky is blue", source="test")

    facts = memory.retrieve_facts()

    assert len(facts) == 1
    assert facts[0].fact == "the sky is blue"
    assert facts[0].source == "test"
    assert facts[0].confidence == 1.0


def test_retrieve_facts_newest_first():
    memory = EpisodicMemory()
    memory.store_fact("fact one")
    memory.store_fact("fact two")

    facts = memory.retrieve_facts()

    assert [f.fact for f in facts] == ["fact two", "fact one"]


def test_retrieve_facts_respects_top_k():
    memory = EpisodicMemory()
    for i in range(5):
        memory.store_fact(f"fact {i}")

    facts = memory.retrieve_facts(top_k=2)

    assert len(facts) == 2


def test_retrieve_facts_filters_by_min_confidence():
    memory = EpisodicMemory()
    memory.store_fact("low confidence fact", confidence=0.2)
    memory.store_fact("high confidence fact", confidence=0.9)

    facts = memory.retrieve_facts(min_confidence=0.5)

    assert len(facts) == 1
    assert facts[0].fact == "high confidence fact"


def test_ttl_expiry():
    memory = EpisodicMemory()
    fact_id = memory.store_fact("short-lived fact", ttl_seconds=0.01)
    assert fact_id in memory._facts

    time.sleep(0.05)
    removed = memory.cleanup_expired()

    assert removed == 1
    assert fact_id not in memory._facts


def test_retrieve_facts_auto_cleans_expired():
    memory = EpisodicMemory()
    memory.store_fact("short-lived fact", ttl_seconds=0.01)
    memory.store_fact("durable fact")

    time.sleep(0.05)
    facts = memory.retrieve_facts()

    assert [f.fact for f in facts] == ["durable fact"]


def test_extract_facts_is_a_stub():
    memory = EpisodicMemory()
    assert memory.extract_facts([]) == []


def test_len():
    memory = EpisodicMemory()
    memory.store_fact("one")
    memory.store_fact("two")

    assert len(memory) == 2
