"""Suite 6 — Award & Activate atomic transaction (T06-01 .. T06-12).

Most of the 11 sub-actions reach into other OCERP modules (tendering, finance,
procurement, documents, contacts).  We monkeypatch each helper so we can
assert (a) every sub-action fired in order and (b) a simulated failure rolls
the transaction back without leaving stage_event rows behind.

The full integration variant of these tests (real Docker postgres + every
OCERP service running) belongs in the OCERP repo's pytest suite.
"""

import uuid
from unittest.mock import AsyncMock, patch

import pytest

from app.modules.precon.services import InvalidPhaseError
from app.modules.precon.services.pipeline_service import PipelineService

pytestmark = pytest.mark.asyncio


# ── Helper: wire up a project in 'bidding' state with a winning bid stub ──


async def _setup_pipeline(session, project_phase_store, *, phase: str = "bidding"):
    project_id = uuid.uuid4()
    bid_id = uuid.uuid4()
    contact_id = uuid.uuid4()
    project_phase_store[project_id] = phase

    bid_stub = type("BidStub", (), {})()
    bid_stub.id = bid_id
    bid_stub.contact_id = contact_id
    bid_stub.total_amount = "12345.67"
    bid_stub.currency = "USD"

    svc = PipelineService(session)

    async def _fake_get_bid(self, _bid_id):  # noqa: ARG001
        return bid_stub

    return svc, project_id, bid_id, contact_id, _fake_get_bid


# ── T06-01: happy path runs all 11 sub-actions ────────────────────────────


async def test_award_activate_executes_all_sub_actions(session, project_phase_store):
    svc, project_id, bid_id, contact_id, fake_get_bid = await _setup_pipeline(session, project_phase_store)

    with (
        patch.object(PipelineService, "_get_bid", fake_get_bid),
        patch.object(PipelineService, "_mark_bid_statuses", AsyncMock()),
        patch.object(PipelineService, "_flip_contact_to_subcontractor", AsyncMock()),
        patch.object(PipelineService, "_promote_budgets", AsyncMock()),
        patch.object(PipelineService, "_create_purchase_order", AsyncMock(return_value=uuid.uuid4())),
        patch.object(PipelineService, "_create_subcontract_draft", AsyncMock(return_value=uuid.uuid4())),
        patch.object(PipelineService, "_notify_winner", AsyncMock()),
        patch.object(PipelineService, "_notify_losers", AsyncMock()),
        patch.object(PipelineService, "_operations_handoff", AsyncMock()),
    ):
        result = await svc.award_and_activate(project_id, bid_id)

    assert len(result.sub_actions_completed) == 11
    assert result.winning_contact_id == contact_id
    assert project_phase_store[project_id] == "awarded"
    events = await svc.events.list_for_project(project_id)
    assert len(events) == 1
    assert events[0].from_stage == "bidding"
    assert events[0].to_stage == "awarded"


# ── T06-02 .. T06-07: per-sub-action assertions covered by mocks above ────


async def test_award_calls_each_sub_action_in_order(session, project_phase_store):
    svc, project_id, bid_id, _contact_id, fake_get_bid = await _setup_pipeline(session, project_phase_store)

    call_log: list[str] = []

    def _record(name: str):
        async def _inner(*_a, **_kw):
            call_log.append(name)
            if name in ("create_purchase_order", "create_subcontract_draft"):
                return uuid.uuid4()
            return None

        return _inner

    with (
        patch.object(PipelineService, "_get_bid", fake_get_bid),
        patch.object(PipelineService, "_mark_bid_statuses", _record("mark_bid_statuses")),
        patch.object(PipelineService, "_flip_contact_to_subcontractor", _record("flip_contact")),
        patch.object(PipelineService, "_promote_budgets", _record("promote_budgets")),
        patch.object(PipelineService, "_create_purchase_order", _record("create_purchase_order")),
        patch.object(PipelineService, "_create_subcontract_draft", _record("create_subcontract_draft")),
        patch.object(PipelineService, "_notify_winner", _record("notify_winner")),
        patch.object(PipelineService, "_notify_losers", _record("notify_losers")),
        patch.object(PipelineService, "_operations_handoff", _record("operations_handoff")),
    ):
        await svc.award_and_activate(project_id, bid_id)

    expected_order = [
        "mark_bid_statuses",
        "flip_contact",
        "promote_budgets",
        "create_purchase_order",
        "create_subcontract_draft",
        "notify_winner",
        "notify_losers",
        "operations_handoff",
    ]
    assert call_log == expected_order


# ── T06-08: failure at promote_budgets rolls back the whole transaction ───


async def test_failure_at_promote_budgets_rolls_back(session, project_phase_store):
    svc, project_id, bid_id, _contact_id, fake_get_bid = await _setup_pipeline(session, project_phase_store)

    async def _boom(*_a, **_kw):
        raise RuntimeError("simulated finance failure")

    with (
        patch.object(PipelineService, "_get_bid", fake_get_bid),
        patch.object(PipelineService, "_mark_bid_statuses", AsyncMock()),
        patch.object(PipelineService, "_flip_contact_to_subcontractor", AsyncMock()),
        patch.object(PipelineService, "_promote_budgets", _boom),
    ):
        with pytest.raises(RuntimeError):
            await svc.award_and_activate(project_id, bid_id)

    # The savepoint rolled back, so no stage_event row should have been
    # persisted to the DB.  (The in-memory phase_store stub can't be rolled
    # back because it isn't transactional — that's why this assertion only
    # checks the DB-level guarantee, which is what production code relies on.)
    events = await svc.events.list_for_project(project_id)
    assert events == [], f"Stage event should not have persisted after rollback: {events}"


# ── T06-09: failure at notify_losers rolls back the whole transaction ─────


async def test_failure_at_notify_losers_rolls_back(session, project_phase_store):
    svc, project_id, bid_id, _contact_id, fake_get_bid = await _setup_pipeline(session, project_phase_store)

    async def _boom(*_a, **_kw):
        raise RuntimeError("simulated notification failure")

    with (
        patch.object(PipelineService, "_get_bid", fake_get_bid),
        patch.object(PipelineService, "_mark_bid_statuses", AsyncMock()),
        patch.object(PipelineService, "_flip_contact_to_subcontractor", AsyncMock()),
        patch.object(PipelineService, "_promote_budgets", AsyncMock()),
        patch.object(PipelineService, "_create_purchase_order", AsyncMock(return_value=uuid.uuid4())),
        patch.object(PipelineService, "_create_subcontract_draft", AsyncMock(return_value=uuid.uuid4())),
        patch.object(PipelineService, "_notify_winner", AsyncMock()),
        patch.object(PipelineService, "_notify_losers", _boom),
    ):
        with pytest.raises(RuntimeError):
            await svc.award_and_activate(project_id, bid_id)

    # See note in test_failure_at_promote_budgets_rolls_back — assert only
    # the DB-level rollback guarantee.
    events = await svc.events.list_for_project(project_id)
    assert events == [], f"Stage event should not have persisted after rollback: {events}"


# ── T06-10 / T06-11: confirmation modal — frontend e2e (skipped here) ─────


@pytest.mark.skip(reason="T06-10 / T06-11 are frontend e2e tests — see Suite 11")
def test_confirmation_modal_lists_eleven_sub_actions():
    pass


# ── T06-12: wrong phase rejected with InvalidPhaseError ───────────────────


async def test_wrong_phase_raises_invalid_phase(session, project_phase_store):
    svc, project_id, bid_id, _contact_id, fake_get_bid = await _setup_pipeline(
        session, project_phase_store, phase="identified"
    )
    with patch.object(PipelineService, "_get_bid", fake_get_bid):
        with pytest.raises(InvalidPhaseError):
            await svc.award_and_activate(project_id, bid_id)
