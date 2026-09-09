"""Exponential backoff with jitter for provider/tool calls."""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from typing import Any


# Deterministic jitter without Math.random-style nondeterminism concerns:
# derive a bounded pseudo-jitter from the attempt index so tests are stable.
def _jitter(attempt: int, base: float) -> float:
    seed = (attempt * 2654435761) % 1000 / 1000.0  # 0..1
    return base * 0.25 * seed


async def retry_async(
    fn: Callable[[], Awaitable[Any]],
    *,
    max_attempts: int = 3,
    base_delay: float = 0.25,
    max_delay: float = 8.0,
    retry_on: tuple[type[Exception], ...] = (Exception,),
    on_retry: Callable[[int, Exception], None] | None = None,
) -> Any:
    """Call fn() with exponential backoff + jitter. Re-raises the last error."""
    last_exc: Exception | None = None
    for attempt in range(max_attempts):
        try:
            return await fn()
        except retry_on as exc:  # noqa: B902
            last_exc = exc
            if on_retry:
                on_retry(attempt, exc)
            if attempt < max_attempts - 1:
                delay = min(base_delay * (2 ** attempt), max_delay) + _jitter(attempt + 1, base_delay)
                await asyncio.sleep(delay)
    assert last_exc is not None
    raise last_exc
