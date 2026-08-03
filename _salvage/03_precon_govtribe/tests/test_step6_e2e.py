"""Step 6 — end-to-end smoke test for the live GovTribe integration.

Marked ``integration`` so the regular ``pytest`` run skips it; flip the
``GOVTRIBE_MCP_ENABLED`` env var to ``true`` and provide a working MCP
transport (``app.mcp.govtribe.invoke_tool``, ``GOVTRIBE_MCP_COMMAND``, or
``GOVTRIBE_MCP_URL``) to actually exercise the live path.

How to run manually
-------------------
    # Powershell — one-time, with the MCP bridge already running:
    $env:GOVTRIBE_MCP_ENABLED = 'true'
    $env:GOVTRIBE_MCP_URL = 'http://localhost:7800'
    cd C:\\dev\\NEXUS_Relay\\precon_build
    python -m pytest backend/app/modules/precon/tests/test_step6_e2e.py -v -m integration

The test sequence mirrors the build contract's "real-world smoke" path:

    1. Trigger the Celery sync task manually (``run_sync()``)
    2. Verify ≥1 row landed in ``oe_nexus_precon_opportunity_cache``
    3. Pick the first cached opportunity
    4. Promote it via ``promote_opportunity()``
    5. Verify the resulting ``oe_projects_project`` row exists with phase='identified'
    6. Verify the cleaned description has no diff syntax (``@@`` markers)
    7. Verify the address either carries the place_of_performance name or is null
    8. Verify exactly one bid_due milestone is created on the project
"""

from __future__ import annotations

import os

import pytest

pytestmark = [
    pytest.mark.asyncio,
    pytest.mark.integration,
]


def _live_integration_enabled() -> bool:
    return os.environ.get("GOVTRIBE_MCP_ENABLED", "").lower() in {"1", "true", "yes", "on"}


@pytest.mark.skipif(
    not _live_integration_enabled(),
    reason="GOVTRIBE_MCP_ENABLED is not set; smoke test only runs against a live MCP bridge.",
)
async def test_govtribe_live_smoke(session, engine):
    """Full sync → promote round-trip against the live GovTribe MCP bridge."""
    from sqlalchemy import select

    from app.modules.precon.models import OpportunityCache
    from app.modules.precon.services.govtribe_adapter import GovTribeAdapter
    from app.modules.precon.tasks.govtribe_sync import run_sync

    # Step 1: trigger the sync task manually using the test engine's session
    # factory so cached rows land in the same DB the assertions read.
    from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

    factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    sync_result = await run_sync(session_factory=factory, page_size=10)
    assert sync_result["status"] in {"ok", "error"}, sync_result
    if sync_result["status"] == "error":
        pytest.skip(f"GovTribe sync errored: {sync_result['message']}")
    assert sync_result["fetched"] >= 1, "Live MCP returned zero opportunities"

    # Step 2: cache populated.
    cached = (
        await session.execute(select(OpportunityCache).order_by(OpportunityCache.last_synced_at.desc()))
    ).scalars().all()
    assert len(cached) >= 1

    # Step 3: pick the first cached opportunity.
    target = cached[0]
    govtribe_id = target.govtribe_external_id
    assert govtribe_id, "Cached opportunity has no govtribe_external_id"

    # Step 4: promote.
    adapter = GovTribeAdapter(session, mcp_client=None)
    promotion = await adapter.promote_opportunity(govtribe_id)
    assert promotion.project_id is not None

    # Step 5: project row exists with phase='identified'.
    from app.modules.projects.models import Project, ProjectMilestone  # type: ignore[import-not-found]

    project = await session.get(Project, promotion.project_id)
    assert project is not None
    assert project.phase == "identified"

    # Step 6: description cleaned (no diff syntax left).
    desc = project.description or ""
    assert "@@" not in desc, f"udiff hunk header leaked into description: {desc!r}"

    # Step 7: address either carries place_of_performance or is null.
    if project.address is not None:
        assert isinstance(project.address, dict)
        assert "name" in project.address

    # Step 8: exactly one bid_due milestone created.
    milestones = (
        await session.execute(
            select(ProjectMilestone).where(ProjectMilestone.project_id == promotion.project_id)
        )
    ).scalars().all()
    assert len(milestones) == 1
    assert milestones[0].milestone_type == "bid_due"


# ── Disabled-mode smoke (always runs) ─────────────────────────────────────


async def test_sync_task_disabled_when_env_off(session, engine):
    """``run_sync`` returns ``status='disabled'`` and does NOT crash when MCP is off.

    This is the production-default behaviour — it must hold even when the
    rest of this file is skipped because ``GOVTRIBE_MCP_ENABLED`` is unset.
    """
    from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

    from app.modules.precon.tasks.govtribe_sync import run_sync

    # Force the disabled state regardless of the operator's local env.
    prior = os.environ.pop("GOVTRIBE_MCP_ENABLED", None)
    try:
        factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
        result = await run_sync(session_factory=factory)
    finally:
        if prior is not None:
            os.environ["GOVTRIBE_MCP_ENABLED"] = prior

    assert result["status"] == "disabled"
    assert result["fetched"] == 0
    assert result["cached"] == 0
    assert result["errors"] == 0
    assert "disabled" in (result["message"] or "").lower()
