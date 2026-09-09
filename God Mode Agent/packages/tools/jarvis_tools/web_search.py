"""Web search abstraction with a deterministic mock fallback provider."""

from __future__ import annotations

import hashlib
from abc import ABC, abstractmethod


class SearchProvider(ABC):
    name = "base"

    @abstractmethod
    async def search(self, query: str, max_results: int = 5) -> list[dict]: ...


class MockSearchProvider(SearchProvider):
    """Deterministic offline results — stable across runs for the same query."""

    name = "mock"

    async def search(self, query: str, max_results: int = 5) -> list[dict]:
        digest = hashlib.sha256(query.encode()).hexdigest()[:8]
        return [
            {
                "title": f"Mock result {i + 1} for '{query[:60]}'",
                "url": f"https://example.com/{digest}/{i + 1}",
                "snippet": f"Deterministic offline snippet {i + 1} about {query[:80]}.",
            }
            for i in range(max_results)
        ]


def get_search_provider(provider: str = "mock") -> SearchProvider:
    # Seam for a real provider (Tavily/Bing/SerpAPI) later; mock is Phase-1 default.
    return MockSearchProvider()


async def web_search(query: str, max_results: int = 5, provider: str = "mock") -> dict:
    p = get_search_provider(provider)
    results = await p.search(query, max_results=max_results)
    return {"provider": p.name, "query": query, "results": results}
