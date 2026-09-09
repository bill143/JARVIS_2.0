"""Provider failover routing: retries, fallback order, hard failure."""

import pytest

from jarvis_adapters.base import ModelAdapter, TransientProviderError
from jarvis_adapters.mock_adapter import MockAdapter
from jarvis_adapters.router import ProviderRouter, build_router
from jarvis_shared.errors import AllProvidersFailed
from jarvis_shared.schemas import Message


class FailingAdapter(ModelAdapter):
    name = "failing"

    def __init__(self):
        self.calls = 0

    def available(self) -> bool:
        return True

    async def complete(self, messages, tools=None, model=None):
        self.calls += 1
        raise TransientProviderError("simulated outage")


class UnconfiguredAdapter(ModelAdapter):
    name = "unconfigured"

    def available(self) -> bool:
        return False

    async def complete(self, messages, tools=None, model=None):
        raise AssertionError("must never be called")


MSGS = [Message(role="user", content="hello")]


async def test_falls_back_to_mock_after_retries():
    failing = FailingAdapter()
    router = ProviderRouter([failing, MockAdapter()], max_retries=2, base_delay=0.01)
    result = await router.complete(MSGS)
    assert result.provider == "mock"
    assert failing.calls == 3  # initial attempt + 2 retries with backoff


async def test_unconfigured_provider_is_skipped():
    router = ProviderRouter([UnconfiguredAdapter(), MockAdapter()], base_delay=0.01)
    result = await router.complete(MSGS)
    assert result.provider == "mock"


async def test_fallbacks_disabled_fails_hard():
    router = ProviderRouter([FailingAdapter(), MockAdapter()], enable_fallbacks=False, max_retries=0, base_delay=0.01)
    with pytest.raises(AllProvidersFailed):
        await router.complete(MSGS)


async def test_build_router_without_keys_routes_to_mock(settings):
    router = build_router(settings)
    result = await router.complete(MSGS)
    assert result.provider == "mock"
    assert "hello" in (result.content or "")
