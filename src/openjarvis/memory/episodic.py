"""Episodic memory — long-term fact storage with confidence, provenance, and TTL."""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from openjarvis.core.types import Message


@dataclass(slots=True)
class EpisodicFact:
    """A single long-term fact, distinct from the rolling conversation kept in
    :class:`openjarvis.sessions.session.SessionStore`.
    """

    fact: str
    confidence: float = 1.0
    source: str = ""
    created_at: float = field(default_factory=time.time)
    expires_at: Optional[float] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def is_expired(self) -> bool:
        """Whether this fact's TTL has elapsed."""
        return self.expires_at is not None and time.time() >= self.expires_at


class EpisodicMemory:
    """In-process long-term fact store with confidence, provenance, and TTL.

    Complements session memory rather than replacing it: sessions hold the
    rolling turn-by-turn conversation, episodic memory holds durable facts
    extracted *from* conversations (e.g. "the user's org uses UEI number
    ...") that should survive well past any one session's decay window.
    """

    def __init__(self) -> None:
        self._facts: Dict[str, EpisodicFact] = {}

    def extract_facts(self, messages: List[Message]) -> List[EpisodicFact]:
        """Extract candidate facts from *messages*.

        Stub for this baseline phase — always returns an empty list.
        Reliable free-form fact extraction (entity/relation extraction,
        dedup against existing facts, contradiction resolution) is a
        substantial project in its own right and deserves a dedicated
        phase rather than a placeholder heuristic that would just be
        thrown away once real extraction lands.
        """
        return []

    def store_fact(
        self,
        fact: str,
        *,
        confidence: float = 1.0,
        source: str = "",
        ttl_seconds: Optional[float] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> str:
        """Store *fact* and return its id."""
        fact_id = uuid.uuid4().hex[:16]
        expires_at = time.time() + ttl_seconds if ttl_seconds is not None else None
        self._facts[fact_id] = EpisodicFact(
            fact=fact,
            confidence=confidence,
            source=source,
            expires_at=expires_at,
            metadata=metadata or {},
        )
        return fact_id

    def retrieve_facts(
        self,
        query: str = "",
        *,
        top_k: int = 5,
        min_confidence: float = 0.0,
    ) -> List[EpisodicFact]:
        """Return up to *top_k* non-expired facts, newest first.

        *query* is accepted for forward API compatibility but unused until
        a real ranking strategy (e.g. delegating to a ``MemoryBackend`` for
        semantic search over fact text) lands in a later phase.

        Newest-first is determined by insertion order (dicts preserve it),
        not by sorting on ``created_at`` — wall-clock resolution can make
        two facts stored microseconds apart compare equal, which would
        make a ``created_at`` sort silently fall back to arbitrary order.
        """
        self.cleanup_expired()
        candidates = [
            f for f in reversed(self._facts.values()) if f.confidence >= min_confidence
        ]
        return candidates[:top_k]

    def cleanup_expired(self) -> int:
        """Remove expired facts. Returns the number removed."""
        expired_ids = [fid for fid, f in self._facts.items() if f.is_expired]
        for fid in expired_ids:
            del self._facts[fid]
        return len(expired_ids)

    def __len__(self) -> int:
        return len(self._facts)


__all__ = ["EpisodicFact", "EpisodicMemory"]
