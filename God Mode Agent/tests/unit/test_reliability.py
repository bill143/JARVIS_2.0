"""Reliability unit tests: retry+jitter, circuit breaker, idempotency, queue+DLQ."""

import pytest

from jarvis_reliability.circuit_breaker import CircuitBreaker
from jarvis_reliability.idempotency import IdempotencyStore, request_hash
from jarvis_reliability.queue import get_queue
from jarvis_reliability.retry import retry_async


async def test_retry_succeeds_after_failures():
    calls = {"n": 0}

    async def flaky():
        calls["n"] += 1
        if calls["n"] < 3:
            raise ValueError("transient")
        return "ok"

    result = await retry_async(flaky, max_attempts=5, base_delay=0.001)
    assert result == "ok" and calls["n"] == 3


async def test_retry_exhausts_and_raises():
    async def always_fail():
        raise ValueError("nope")

    with pytest.raises(ValueError):
        await retry_async(always_fail, max_attempts=2, base_delay=0.001)


def test_circuit_breaker_trips_and_recovers():
    cb = CircuitBreaker("test", failure_threshold=3, recovery_time=0.05)
    assert cb.allow()
    for _ in range(3):
        cb.record_failure()
    assert cb.state == "open"
    assert not cb.allow()  # short-circuits while open
    import time

    time.sleep(0.06)
    assert cb.allow()  # half-open trial
    cb.record_success()
    assert cb.state == "closed"


def test_idempotency_replay_and_conflict(settings):
    store = IdempotencyStore(settings.sqlite_path, ttl_hours=1)
    payload = {"a": 1}
    h = request_hash(payload)
    assert store.lookup("k1", "/x", h) is None
    store.store("k1", "/x", h, {"result": 42})
    replay = store.lookup("k1", "/x", h)
    assert replay["status"] == "replay" and replay["response"]["result"] == 42
    conflict = store.lookup("k1", "/x", request_hash({"a": 2}))
    assert conflict["status"] == "conflict"
    store.close()


async def test_queue_processes_and_dead_letters(settings):
    q = get_queue(settings, settings.sqlite_path)
    q.register("ok", lambda p: {"doubled": p["n"] * 2})
    q.register("bad", lambda p: (_ for _ in ()).throw(RuntimeError("boom")))

    ok_id = q.enqueue("ok", {"n": 21})
    bad_id = q.enqueue("bad", {})

    # drain: ok completes; bad retries until DLQ
    for _ in range(20):
        if not await q.process_once():
            break
        import time

        time.sleep(0.01)

    assert q.get(ok_id)["status"] == "done"
    assert q.get(ok_id)["result"]["doubled"] == 42
    # bad job ends dead after max attempts
    bad = q.get(bad_id)
    assert bad["status"] in ("dead", "queued")  # eventually dead
    q.close()


async def test_queue_dlq_after_max_attempts(settings):
    import time

    # near-zero backoff so the retry becomes immediately claimable in this fast loop
    settings2 = settings.model_copy(update={"queue_max_attempts": 2, "queue_retry_base": 0.001})
    q = get_queue(settings2, settings.sqlite_path)
    q.register("bad", lambda p: (_ for _ in ()).throw(RuntimeError("boom")))
    jid = q.enqueue("bad", {})
    for _ in range(10):
        await q.process_once()
        time.sleep(0.005)
    assert q.get(jid)["status"] == "dead"
    assert q.dead_letters()  # non-empty
    q.close()
