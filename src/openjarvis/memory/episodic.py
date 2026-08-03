"""Episodic memory — long-term facts with confidence, provenance, and TTL.

Phase 1 baseline: facts are extracted from conversation turns with a
lightweight rule-based heuristic (no NLP model dependency), scored for
retrieval via lexical word overlap, and expired via an explicit TTL rather
than a background sweep. A confidence-weighted embedding-based retriever can
replace ``retrieve_facts`` in a later phase without changing the public
interface.
"""

from __future__ import annotations

import re
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from openjarvis.core.types import Message, Role

_SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?])\s+")


@dataclass(slots=True)
class EpisodicFact:
    """A single long-term fact with provenance and optional expiry."""

    fact: str
    confidence: float = 1.0
    source: str = ""
    expires_at: Optional[float] = None
    metadata: Dict[str, Any] = field(default_factory=dict)


class EpisodicMemory:
    """Store and recall long-term facts extracted from conversations."""

    def __init__(self) -> None:
        self.facts: Dict[str, EpisodicFact] = {}

    def store_fact(
        self,
        fact: str,
        *,
        confidence: float = 1.0,
        source: str = "",
        ttl_hours: Optional[float] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> str:
        """Store a fact with confidence, provenance, and optional TTL.

        Returns the generated fact id.
        """
        fact_id = uuid.uuid4().hex
        expires_at = time.time() + ttl_hours * 3600 if ttl_hours is not None else None
        self.facts[fact_id] = EpisodicFact(
            fact=fact,
            confidence=confidence,
            source=source,
            expires_at=expires_at,
            metadata=metadata or {},
        )
        return fact_id

    def retrieve_facts(self, query: str, k: int = 5) -> List[EpisodicFact]:
        """Return up to *k* facts relevant to *query*, most relevant first.

        Relevance is lexical word overlap between *query* and each fact,
        weighted by the fact's stored confidence. Facts with zero overlap
        are excluded. Expired facts are purged before scoring.
        """
        self.cleanup_expired()
        if k <= 0:
            return []

        query_words = set(query.lower().split())
        scored = []
        for fact in self.facts.values():
            overlap = len(query_words & set(fact.fact.lower().split()))
            if overlap:
                scored.append((overlap * fact.confidence, fact))

        scored.sort(key=lambda pair: pair[0], reverse=True)
        return [fact for _, fact in scored[:k]]

    def cleanup_expired(self) -> None:
        """Remove facts whose TTL has elapsed."""
        now = time.time()
        expired = [
            fact_id
            for fact_id, fact in self.facts.items()
            if fact.expires_at is not None and fact.expires_at <= now
        ]
        for fact_id in expired:
            del self.facts[fact_id]

    def extract_facts(
        self,
        conversation: List[Message],
        *,
        source: str = "conversation",
        default_confidence: float = 0.6,
    ) -> List[EpisodicFact]:
        """Heuristically extract candidate facts from user turns.

        Rule-based only: declarative sentences (i.e. not ending in ``?``)
        with at least four words are kept. Returned facts are not persisted
        — pass any of them to :meth:`store_fact` to keep them.
        """
        candidates: List[EpisodicFact] = []
        for message in conversation:
            if message.role is not Role.USER:
                continue
            for sentence in _SENTENCE_SPLIT_RE.split(message.content.strip()):
                sentence = sentence.strip()
                if not sentence or sentence.endswith("?"):
                    continue
                if len(sentence.split()) < 4:
                    continue
                candidates.append(
                    EpisodicFact(
                        fact=sentence,
                        confidence=default_confidence,
                        source=source,
                    )
                )
        return candidates


__all__ = ["EpisodicFact", "EpisodicMemory"]
