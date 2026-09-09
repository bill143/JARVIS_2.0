"""Basic in-memory per-client rate limiting (requests per minute)."""

from __future__ import annotations

import time
from collections import defaultdict, deque

WINDOW_SECONDS = 60.0


class RateLimiter:
    def __init__(self, limit_per_minute: int):
        self.limit = max(1, limit_per_minute)
        self._hits: dict[str, deque[float]] = defaultdict(deque)

    def allow(self, client_key: str) -> tuple[bool, float]:
        """Returns (allowed, retry_after_seconds)."""
        now = time.monotonic()
        hits = self._hits[client_key]
        while hits and now - hits[0] > WINDOW_SECONDS:
            hits.popleft()
        if len(hits) >= self.limit:
            return False, max(1.0, WINDOW_SECONDS - (now - hits[0]))
        hits.append(now)
        return True, 0.0
