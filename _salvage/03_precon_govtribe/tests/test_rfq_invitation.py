"""Suite 3 — RFQ invitation 8-state machine (T03-01 .. T03-12)."""

import uuid

import pytest

from app.modules.precon.services import ForbiddenTransitionError
from app.modules.precon.services.rfq_invitation_service import RFQInvitationService

pytestmark = pytest.mark.asyncio


# ── T03-01: fresh invitation defaults to INVITED ───────────────────────────


async def test_create_invitation_starts_invited(session, rfq_id, contact_id):
    svc = RFQInvitationService(session)
    invitation = await svc.invite(rfq_id=rfq_id, contact_id=contact_id)
    assert invitation.status == "INVITED"
    assert invitation.invited_at is not None
    assert invitation.responded_at is None


# ── T03-02 .. T03-08: legal transitions ────────────────────────────────────


@pytest.mark.parametrize(
    ("path"),
    [
        ["VIEWED"],                          # T03-02
        ["VIEWED", "ACCEPTED"],              # T03-03
        ["VIEWED", "ACCEPTED", "SUBMITTED"], # T03-04
        ["VIEWED", "ACCEPTED", "SUBMITTED", "AWARDED"],     # T03-05
        ["VIEWED", "ACCEPTED", "SUBMITTED", "NOT_AWARDED"], # T03-06
        ["DECLINED"],                        # T03-07
        ["IGNORED"],                         # T03-08
    ],
)
async def test_legal_transition_path(session, rfq_id, contact_id, path):
    svc = RFQInvitationService(session)
    invitation = await svc.invite(rfq_id=rfq_id, contact_id=contact_id)
    for state in path:
        await svc.transition(invitation.id, to=state)
    refreshed = await svc.repo.get_by_id(invitation.id)
    assert refreshed.status == path[-1]
    if path[-1] == "VIEWED":
        assert refreshed.last_viewed_at is not None
    else:
        assert refreshed.responded_at is not None


# ── T03-09 / T03-10: terminal states reject any transition ────────────────


@pytest.mark.parametrize("terminal", ["AWARDED", "NOT_AWARDED", "DECLINED", "IGNORED"])
async def test_terminal_state_rejects_any_transition(session, rfq_id, contact_id, terminal):
    svc = RFQInvitationService(session)
    invitation = await svc.invite(rfq_id=rfq_id, contact_id=contact_id)
    # Drive into the terminal state via the shortest legal path.
    if terminal == "AWARDED":
        for s in ("VIEWED", "ACCEPTED", "SUBMITTED", "AWARDED"):
            await svc.transition(invitation.id, to=s)
    elif terminal == "NOT_AWARDED":
        for s in ("VIEWED", "ACCEPTED", "SUBMITTED", "NOT_AWARDED"):
            await svc.transition(invitation.id, to=s)
    elif terminal == "DECLINED":
        await svc.transition(invitation.id, to="DECLINED")
    elif terminal == "IGNORED":
        await svc.transition(invitation.id, to="IGNORED")
    # Any further transition must raise.
    with pytest.raises(ForbiddenTransitionError):
        await svc.transition(invitation.id, to="VIEWED")


# ── T03-11: ACCEPTED -> VIEWED (backward) rejected ────────────────────────


async def test_backward_jump_rejected(session, rfq_id, contact_id):
    svc = RFQInvitationService(session)
    invitation = await svc.invite(rfq_id=rfq_id, contact_id=contact_id)
    await svc.transition(invitation.id, to="VIEWED")
    await svc.transition(invitation.id, to="ACCEPTED")
    with pytest.raises(ForbiddenTransitionError):
        await svc.transition(invitation.id, to="VIEWED")


# ── T03-12: 3 contacts on 1 RFQ — distinct rows, all in INVITED ───────────


async def test_three_contacts_on_one_rfq(session, rfq_id):
    svc = RFQInvitationService(session)
    contact_ids = [uuid.uuid4() for _ in range(3)]
    for cid in contact_ids:
        await svc.invite(rfq_id=rfq_id, contact_id=cid)
    rows = await svc.list_for_rfq(rfq_id)
    assert len(rows) == 3
    assert {r.contact_id for r in rows} == set(contact_ids)
    assert {r.status for r in rows} == {"INVITED"}
