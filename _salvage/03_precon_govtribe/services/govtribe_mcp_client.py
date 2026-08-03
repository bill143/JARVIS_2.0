"""GovTribe MCP client — production wrapper around the MCP bridge.

The adapter layer (``govtribe_adapter.py``) consumes any object with the
contract:

    async def search_opportunities(*, limit: int = 25) -> list[dict]
    async def get_opportunity(govtribe_id: str) -> dict | None

This module ships two implementations:

* :class:`GovTribeMCPClient` — the live wrapper.  Disabled by default so
  unit tests never accidentally hit the upstream; enable in production by
  setting ``GOVTRIBE_MCP_ENABLED=true``.
* :class:`DisabledGovTribeMCPClient` — explicit "off" sentinel.  Every
  method raises :class:`GovTribeConnectionError` with a clear message so
  downstream code (the heartbeat task) reports the right alert.

The actual MCP transport is injected via the ``mcp_invoker`` constructor
argument so the client stays unit-testable without spinning up an MCP
session.  The default invoker walks a small list of candidate transports
in priority order:

1. ``app.mcp.govtribe.invoke_tool(tool_name, params)`` — when OCERP wires
   GovTribe through its own MCP layer (preferred).
2. The ``mcp`` package's stdio bridge — when the GovTribe MCP server is
   running as a sidecar process.
3. A best-effort HTTP shim against ``GOVTRIBE_MCP_URL`` — last resort.

If none of the transports resolve, the client raises
``GovTribeConnectionError`` rather than guessing — operators must wire one
of the three transports explicitly.
"""

from __future__ import annotations

import logging
import os
from typing import Any, Awaitable, Callable, Protocol

from app.modules.precon.services import GovTribeConnectionError

logger = logging.getLogger(__name__)


# ── Configuration ─────────────────────────────────────────────────────────


def is_govtribe_enabled() -> bool:
    """Return True when ``GOVTRIBE_MCP_ENABLED`` env var is truthy.

    The env var defaults to false so a misconfigured production deployment
    runs blind rather than calling out to an unconfigured upstream.  Tests
    and CI inherit the default.
    """
    return os.environ.get("GOVTRIBE_MCP_ENABLED", "").lower() in {"1", "true", "yes", "on"}


# ── Transport protocol ───────────────────────────────────────────────────


class MCPInvoker(Protocol):
    """Minimal protocol the live client expects from its transport.

    Returning ``Any`` rather than ``dict`` so the protocol fits both
    raw-JSON transports and structured-call transports.
    """

    async def __call__(self, tool_name: str, params: dict[str, Any]) -> Any: ...


# ── Live client ───────────────────────────────────────────────────────────


class GovTribeMCPClient:
    """Live MCP wrapper for the GovTribe Search_Federal_Contract_Opportunities tool.

    Pass an explicit ``mcp_invoker`` from tests so the call path stays
    deterministic.  In production the default invoker resolves one of the
    three transport candidates documented in the module docstring.
    """

    DEFAULT_TOOL_SEARCH = "Search_Federal_Contract_Opportunities"
    DEFAULT_TOOL_GET = "Get_Federal_Contract_Opportunity"

    def __init__(
        self,
        *,
        mcp_invoker: MCPInvoker | Callable[[str, dict[str, Any]], Awaitable[Any]] | None = None,
        search_tool_name: str | None = None,
        get_tool_name: str | None = None,
    ) -> None:
        self._invoker = mcp_invoker or _resolve_default_invoker()
        self._search_tool = search_tool_name or self.DEFAULT_TOOL_SEARCH
        self._get_tool = get_tool_name or self.DEFAULT_TOOL_GET

    async def search_opportunities(
        self,
        *,
        limit: int = 25,
        filters: dict[str, Any] | None = None,
    ) -> list[dict[str, Any]]:
        """Return the latest ``limit`` opportunities matching ``filters``.

        ``filters`` is forwarded to the MCP tool unchanged; common keys
        include ``naics_code``, ``set_aside_type``, ``posted_after``.
        """
        if self._invoker is None:
            raise GovTribeConnectionError(
                "GovTribe MCP transport not configured; set GOVTRIBE_MCP_ENABLED "
                "and provide an invoker via app.mcp.govtribe.invoke_tool, the "
                "stdio bridge, or GOVTRIBE_MCP_URL."
            )
        params: dict[str, Any] = {"limit": int(limit)}
        if filters:
            params.update(filters)
        try:
            raw = await self._invoker(self._search_tool, params)
        except GovTribeConnectionError:
            raise
        except Exception as exc:
            raise GovTribeConnectionError(f"GovTribe search failed: {exc}") from exc
        return _normalize_search_results(raw)

    async def get_opportunity(self, govtribe_id: str) -> dict[str, Any] | None:
        """Fetch one opportunity by its 32-char hex ``govtribe_id``."""
        if self._invoker is None:
            raise GovTribeConnectionError("GovTribe MCP transport not configured")
        try:
            raw = await self._invoker(self._get_tool, {"govtribe_id": govtribe_id})
        except GovTribeConnectionError:
            raise
        except Exception as exc:
            raise GovTribeConnectionError(f"GovTribe get failed: {exc}") from exc
        if raw is None:
            return None
        if isinstance(raw, dict):
            return raw
        # Some transports wrap the payload in a content envelope.
        if isinstance(raw, list) and raw:
            first = raw[0]
            return first if isinstance(first, dict) else None
        return None


class DisabledGovTribeMCPClient:
    """Sentinel client used when ``GOVTRIBE_MCP_ENABLED`` is false.

    Every method raises :class:`GovTribeConnectionError` so the heartbeat
    task surfaces a clean "stale" alert rather than appearing healthy.
    """

    async def search_opportunities(
        self,
        *,
        limit: int = 25,  # noqa: ARG002
        filters: dict[str, Any] | None = None,  # noqa: ARG002
    ) -> list[dict[str, Any]]:
        raise GovTribeConnectionError("GovTribe MCP integration is disabled (GOVTRIBE_MCP_ENABLED=false)")

    async def get_opportunity(self, govtribe_id: str) -> dict[str, Any] | None:  # noqa: ARG002
        raise GovTribeConnectionError("GovTribe MCP integration is disabled (GOVTRIBE_MCP_ENABLED=false)")


def get_default_mcp_client() -> GovTribeMCPClient | DisabledGovTribeMCPClient:
    """Process-wide factory honoring the env-var gate.

    The Celery sync task and the FastAPI dependency both call this so
    flipping ``GOVTRIBE_MCP_ENABLED`` is the only switch needed to turn
    live integration on.
    """
    if not is_govtribe_enabled():
        return DisabledGovTribeMCPClient()
    return GovTribeMCPClient()


# ── Transport resolution ──────────────────────────────────────────────────


def _resolve_default_invoker() -> MCPInvoker | None:
    """Walk the three candidate transports in priority order.

    Returns ``None`` when none resolve — the live client raises a clear
    ``GovTribeConnectionError`` with operator-actionable text on first use.
    """
    invoker = _try_resolve_app_mcp()
    if invoker is not None:
        logger.debug("GovTribe MCP using app.mcp.govtribe transport")
        return invoker
    invoker = _try_resolve_stdio()
    if invoker is not None:
        logger.debug("GovTribe MCP using stdio transport")
        return invoker
    invoker = _try_resolve_http()
    if invoker is not None:
        logger.debug("GovTribe MCP using HTTP transport")
        return invoker
    return None


def _try_resolve_app_mcp() -> MCPInvoker | None:
    try:
        from app.mcp.govtribe import invoke_tool  # type: ignore[import-not-found]

        async def _invoke(tool: str, params: dict[str, Any]) -> Any:
            return await invoke_tool(tool, params)

        return _invoke
    except Exception:
        return None


def _try_resolve_stdio() -> MCPInvoker | None:
    try:
        # Lazy import — keep mcp out of the require-list when unused.
        from mcp import ClientSession  # type: ignore[import-not-found]
        from mcp.client.stdio import stdio_client  # type: ignore[import-not-found]
    except Exception:
        return None

    server_command = os.environ.get("GOVTRIBE_MCP_COMMAND")
    if not server_command:
        return None

    async def _invoke(tool: str, params: dict[str, Any]) -> Any:
        # Each call opens a fresh subprocess — pricey but isolated.  The
        # production deployment should override this invoker with a
        # long-lived session via app.mcp.govtribe.invoke_tool.
        async with stdio_client(server_command) as (read, write):
            async with ClientSession(read, write) as session:
                response = await session.call_tool(tool, params)
                return response

    return _invoke


def _try_resolve_http() -> MCPInvoker | None:
    base_url = os.environ.get("GOVTRIBE_MCP_URL")
    if not base_url:
        return None
    try:
        import httpx  # type: ignore[import-not-found]
    except Exception:
        return None

    async def _invoke(tool: str, params: dict[str, Any]) -> Any:
        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.post(
                f"{base_url.rstrip('/')}/tools/{tool}",
                json={"params": params},
            )
            resp.raise_for_status()
            return resp.json()

    return _invoke


# ── Response normalisation ───────────────────────────────────────────────


def _normalize_search_results(raw: Any) -> list[dict[str, Any]]:
    """Coerce whatever the transport returned into a list of opportunity dicts.

    Different MCP transports wrap responses differently:

    * direct list of dicts
    * ``{"results": [...]}`` envelope
    * ``{"content": [{"type": "text", "text": "..."}]}`` (MCP CallTool result)

    For the CallTool envelope we fall through to ``[]`` rather than guess
    at JSON parsing — the operator should wire a transport that returns
    structured results.
    """
    if raw is None:
        return []
    if isinstance(raw, list):
        return [item for item in raw if isinstance(item, dict)]
    if isinstance(raw, dict):
        if "results" in raw and isinstance(raw["results"], list):
            return [item for item in raw["results"] if isinstance(item, dict)]
        if "opportunities" in raw and isinstance(raw["opportunities"], list):
            return [item for item in raw["opportunities"] if isinstance(item, dict)]
        # Single-item response.
        if "govtribe_id" in raw:
            return [raw]
    return []
