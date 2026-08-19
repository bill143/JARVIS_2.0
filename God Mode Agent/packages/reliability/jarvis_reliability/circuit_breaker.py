"""Circuit breaker per provider/tool class: closed -> open -> half-open -> closed."""

from __future__ import annotations

import time


class CircuitOpen(Exception):
    """Raised when a call is short-circuited because the breaker is open."""


class CircuitBreaker:
    def __init__(self, name: str, failure_threshold: int = 5, recovery_time: float = 15.0, half_open_max: int = 1):
        self.name = name
        self.failure_threshold = failure_threshold
        self.recovery_time = recovery_time
        self.half_open_max = half_open_max
        self.state = "closed"
        self._failures = 0
        self._opened_at = 0.0
        self._half_open_calls = 0

    def _now(self) -> float:
        return time.monotonic()

    def allow(self) -> bool:
        if self.state == "closed":
            return True
        if self.state == "open":
            if self._now() - self._opened_at >= self.recovery_time:
                self.state = "half_open"
                self._half_open_calls = 0
                return True
            return False
        # half_open: allow a limited number of trial calls
        if self._half_open_calls < self.half_open_max:
            self._half_open_calls += 1
            return True
        return False

    def record_success(self) -> None:
        self._failures = 0
        self.state = "closed"
        self._half_open_calls = 0

    def record_failure(self) -> None:
        self._failures += 1
        if self.state == "half_open" or self._failures >= self.failure_threshold:
            self.state = "open"
            self._opened_at = self._now()

    def snapshot(self) -> dict:
        return {"name": self.name, "state": self.state, "failures": self._failures}


class CircuitRegistry:
    def __init__(self, **defaults):
        self._defaults = defaults
        self._breakers: dict[str, CircuitBreaker] = {}

    def get(self, name: str) -> CircuitBreaker:
        if name not in self._breakers:
            self._breakers[name] = CircuitBreaker(name, **self._defaults)
        return self._breakers[name]

    def snapshot(self) -> list[dict]:
        return [b.snapshot() for b in self._breakers.values()]
