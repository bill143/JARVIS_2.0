"""GovTribe → opportunity-cache sync task.

Runs every 15 minutes (matches the build contract's heartbeat cadence).
Fetches the latest federal opportunities from the live MCP bridge and
upserts them into ``oe_nexus_precon_opportunity_cache``.

The task is intentionally idempotent: the cache repository keys on
``govtribe_external_id`` and refreshes ``last_synced_at`` + ``payload`` on
each run, so a second invocation in the same window is a no-op for any
unchanged opportunity and a refresh for an updated one.

When the live MCP integration is disabled (``GOVTRIBE_MCP_ENABLED`` not
set) the task fails fast with a clear log line.  The heartbeat task picks
up the resulting stale cache and fires the contracted alert.
"""

from __future__ import annotations

import logging
from typing import Any

from app.modules.precon.services import GovTribeConnectionError
from app.modules.precon.services.govtribe_adapter import GovTribeAdapter
from app.modules.precon.services.govtribe_mcp_client import (
    DisabledGovTribeMCPClient,
    get_default_mcp_client,
    is_govtribe_enabled,
)

logger = logging.getLogger(__name__)

#: Number of opportunities to pull per sync window.  Held loose so
#: operators can override via env without redeploying the worker.
SYNC_PAGE_SIZE: int = int(__import__("os").environ.get("GOVTRIBE_SYNC_PAGE_SIZE", "50"))

#: Cron interval that drives the beat schedule.  Mirrored from
#: ``heartbeat.BEAT_INTERVAL_SECONDS`` so the two stay in lock-step.
SYNC_INTERVAL_SECONDS: int = 15 * 60


async def run_sync(
    session_factory: Any | None = None,
    *,
    mcp_client: Any | None = None,
    page_size: int | None = None,
) -> dict[str, Any]:
    """Pull the latest opportunities from GovTribe and cache them.

    Returns a structured result dict the heartbeat task and the dashboard
    can render:

        {
            "status": "ok" | "disabled" | "error",
            "fetched": int,
            "cached": int,
            "errors": int,
            "message": str | None,
        }

    Never raises — the Celery worker stays alive on transport failures.
    """
    factory = session_factory or _default_session_factory()
    if factory is None:
        message = "No async session factory available; skipping GovTribe sync"
        logger.warning(message)
        return _result(status="error", fetched=0, cached=0, errors=1, message=message)

    client = mcp_client if mcp_client is not None else get_default_mcp_client()
    if isinstance(client, DisabledGovTribeMCPClient) or not is_govtribe_enabled():
        message = "GovTribe MCP integration disabled (GOVTRIBE_MCP_ENABLED=false)"
        logger.info(message)
        return _result(status="disabled", fetched=0, cached=0, errors=0, message=message)

    pull = page_size or SYNC_PAGE_SIZE
    fetched = cached = errors = 0

    async with factory() as session:
        adapter = GovTribeAdapter(session, mcp_client=client)
        try:
            opps = await adapter.fetch_opportunities(limit=pull)
        except GovTribeConnectionError as exc:
            logger.warning("GovTribe sync — fetch failed: %s", exc)
            return _result(status="error", fetched=0, cached=0, errors=1, message=str(exc))
        except Exception as exc:  # noqa: BLE001 — never crash the worker
            logger.exception("GovTribe sync — unexpected fetch error")
            return _result(status="error", fetched=0, cached=0, errors=1, message=str(exc))

        fetched = len(opps)
        for opp in opps:
            try:
                await adapter.cache_opportunity(opp)
                cached += 1
            except Exception as exc:  # noqa: BLE001 — count, log, keep going
                errors += 1
                logger.warning(
                    "GovTribe sync — cache failed for %s: %s",
                    opp.get("govtribe_id") or opp.get("solicitation_number") or "<unknown>",
                    exc,
                )
        try:
            await session.commit()
        except Exception:  # noqa: BLE001
            logger.exception("GovTribe sync — commit failed; rolling back")
            await session.rollback()
            errors += 1

    logger.info(
        "GovTribe sync complete: fetched=%d cached=%d errors=%d", fetched, cached, errors,
    )
    status = "ok" if errors == 0 else "error"
    return _result(status=status, fetched=fetched, cached=cached, errors=errors, message=None)


# ── Helpers ────────────────────────────────────────────────────────────────


def _default_session_factory() -> Any | None:
    try:
        from app.database import async_session_factory  # type: ignore[import-not-found]

        return async_session_factory
    except Exception:
        return None


def _result(
    *,
    status: str,
    fetched: int,
    cached: int,
    errors: int,
    message: str | None,
) -> dict[str, Any]:
    return {
        "status": status,
        "fetched": fetched,
        "cached": cached,
        "errors": errors,
        "message": message,
    }


# ── Optional Celery binding ───────────────────────────────────────────────


def _bind_to_celery() -> None:
    """Register the sync task with the project's Celery app, if present."""
    try:
        from app.core.celery_app import celery_app  # type: ignore[import-not-found]
    except Exception:
        return
    try:
        from celery.schedules import schedule  # type: ignore[import-not-found]
    except Exception:
        return

    @celery_app.task(name="nexus_precon.govtribe_sync")
    def _task() -> dict[str, Any]:
        import asyncio

        return asyncio.get_event_loop().run_until_complete(run_sync())

    existing_schedule = getattr(celery_app.conf, "beat_schedule", None) or {}
    celery_app.conf.beat_schedule = {
        **existing_schedule,
        "nexus_precon.govtribe_sync": {
            "task": "nexus_precon.govtribe_sync",
            "schedule": schedule(SYNC_INTERVAL_SECONDS),
        },
    }


_bind_to_celery()
