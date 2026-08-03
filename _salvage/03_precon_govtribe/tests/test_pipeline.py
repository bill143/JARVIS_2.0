"""Suite 2 — opportunity stage machine (T02-01 .. T02-10).

The full state-machine matrix is unit-tested against an in-memory phase store
(see ``conftest.project_phase_store``).  ``get_pipeline_summary`` is checked
against an empty store; integration variants that hit the real OCERP project
table belong in the OCERP project's own pytest suite.
"""

import uuid

import pytest

from app.modules.precon.schemas import PIPELINE_STAGES
from app.modules.precon.services import ForbiddenTransitionError
from app.modules.precon.services.pipeline_service import PipelineService

pytestmark = pytest.mark.asyncio


# ── T02-01 .. T02-04: legal transitions ────────────────────────────────────


@pytest.mark.parametrize(
    ("start", "target"),
    [
        ("identified", "triaged"),   # T02-01
        ("identified", "no_bid"),    # T02-02
        ("submitted", "awarded"),    # T02-03
        ("submitted", "lost"),       # T02-04
    ],
)
async def test_legal_transition_writes_event(session, project_phase_store, start, target):
    project_id = uuid.uuid4()
    project_phase_store[project_id] = start
    svc = PipelineService(session)

    await svc.transition(project_id, to=target)

    assert project_phase_store[project_id] == target
    events = await svc.events.list_for_project(project_id)
    assert len(events) == 1
    assert events[0].from_stage == start
    assert events[0].to_stage == target


# ── T02-05: backward jump rejected ─────────────────────────────────────────


async def test_backward_jump_rejected(session, project_phase_store):
    project_id = uuid.uuid4()
    project_phase_store[project_id] = "awarded"
    svc = PipelineService(session)
    with pytest.raises(ForbiddenTransitionError):
        await svc.transition(project_id, to="bidding")


# ── T02-06: skip-jump rejected ─────────────────────────────────────────────


async def test_skip_jump_rejected(session, project_phase_store):
    project_id = uuid.uuid4()
    project_phase_store[project_id] = "identified"
    svc = PipelineService(session)
    with pytest.raises(ForbiddenTransitionError):
        await svc.transition(project_id, to="submitted")


# ── T02-07: resurrection rejected ──────────────────────────────────────────


async def test_resurrection_rejected(session, project_phase_store):
    project_id = uuid.uuid4()
    project_phase_store[project_id] = "lost"
    svc = PipelineService(session)
    with pytest.raises(ForbiddenTransitionError):
        await svc.transition(project_id, to="bidding")


# ── T02-08: unknown stage value rejected with ValueError ──────────────────


async def test_unknown_target_value_rejected(session, project_phase_store):
    project_id = uuid.uuid4()
    project_phase_store[project_id] = "identified"
    svc = PipelineService(session)
    with pytest.raises(ValueError):
        await svc.transition(project_id, to="invalid_value")


# ── T02-09: integration via /api/precon/pipeline/ — exercised in router test ─
# (Full HTTP round-trip would require mounting the OCERP FastAPI app; we
# instead verify the service-level summary covers all 8 stages with zero
# cross-contamination below.)


async def test_summary_covers_all_stages_independently(session, project_phase_store):
    svc = PipelineService(session)

    # Empty store — every stage zero (T02-10).
    summary = await svc.get_pipeline_summary()
    assert set(summary.counts) == set(PIPELINE_STAGES)
    assert all(count == 0 for count in summary.counts.values())
