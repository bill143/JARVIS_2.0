"""Session memory — rolling short-term conversation state.

Stores recent query/response turns and evicts the oldest ones once the
running token count exceeds a configured budget. This is distinct from
``core.types.Conversation``, which caps by message *count*; ``SessionMemory``
caps by an approximate *token* count and pairs each turn with metadata, which
is what context assembly (see ``tools.storage.context``) needs to budget
against.
"""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


def _count_tokens(text: str) -> int:
    """Approximate token count via whitespace split.

    Matches the convention used by ``tools.storage.context._count_tokens``.
    """
    return len(text.split())


@dataclass(slots=True)
class SessionMemoryItem:
    """A single stored query/response turn."""

    id: str
    query: str
    response: str
    metadata: Dict[str, Any] = field(default_factory=dict)
    timestamp: float = 0.0


class SessionMemory:
    """Rolling window of recent conversation turns, trimmed to a token budget.

    Parameters
    ----------
    max_tokens:
        Approximate token ceiling for all stored turns combined. Oldest
        turns are evicted first once this is exceeded.
    """

    def __init__(self, max_tokens: int = 8192) -> None:
        self.max_tokens = max_tokens
        self.items: List[SessionMemoryItem] = []

    def store(
        self,
        query: str,
        response: str,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> str:
        """Store a query/response turn, trim if needed, and return its id."""
        item = SessionMemoryItem(
            id=uuid.uuid4().hex,
            query=query,
            response=response,
            metadata=metadata or {},
            timestamp=time.time(),
        )
        self.items.append(item)
        self._trim()
        return item.id

    def retrieve(self, k: int = 5) -> List[SessionMemoryItem]:
        """Return up to the last *k* turns, oldest first."""
        if k <= 0:
            return []
        return self.items[-k:]

    def clear(self) -> None:
        """Remove all stored turns."""
        self.items.clear()

    def summarize(self) -> str:
        """Render the stored turns as a plain-text transcript."""
        return "\n".join(
            f"User: {item.query}\nAssistant: {item.response}" for item in self.items
        )

    def _total_tokens(self) -> int:
        return sum(
            _count_tokens(item.query) + _count_tokens(item.response)
            for item in self.items
        )

    def _trim(self) -> None:
        while self.items and self._total_tokens() > self.max_tokens:
            self.items.pop(0)


__all__ = ["SessionMemory", "SessionMemoryItem"]
