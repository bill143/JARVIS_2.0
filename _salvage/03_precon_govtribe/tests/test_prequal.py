"""Suite 8 — prequalification event log (T08-01 .. T08-05)."""

import uuid
from datetime import UTC, datetime, timedelta

import pytest

from app.modules.precon.repository import PrequalificationRepository
from app.modules.precon.services.prequalification_service import PrequalificationService

pytestmark = pytest.mark.asyncio


# ── T08-01: update_prequal writes an audit row ────────────────────────────


async def test_update_prequal_writes_audit_row(session, contact_id, user_id):
    svc = PrequalificationService(session)
    expires = datetime.now(UTC) + timedelta(days=365)
    event = await svc.update_prequal(
        contact_id,
        to="approved",
        reason="Annual renewal",
        expires_at=expires,
        changed_by=user_id,
    )
    assert event.contact_id == contact_id
    assert event.to_status == "approved"
    assert event.from_status is None  # no prior event
    assert event.changed_by == user_id


# ── T08-02 + T08-03: repository exposes neither update nor delete ─────────


def test_prequal_repository_is_append_only():
    members = {n for n in dir(PrequalificationRepository) if not n.startswith("_")}
    assert "update" not in members, "PrequalificationRepository must not expose update()"
    assert "delete" not in members, "PrequalificationRepository must not expose delete()"


# ── T08-04: 3 events on a contact returned in chronological order ─────────


async def test_three_events_returned_in_order(session, contact_id):
    svc = PrequalificationService(session)
    for status in ("pending", "in_review", "approved"):
        await svc.update_prequal(contact_id, to=status, reason=f"set to {status}")

    events = await svc.list_events(contact_id)
    assert [e.to_status for e in events] == ["pending", "in_review", "approved"]


# ── T08-05: expired prequal flagged with days_since_expiry ───────────────


async def test_expired_prequal_reports_days_since(session, contact_id):
    svc = PrequalificationService(session)
    expired = datetime.now(UTC) - timedelta(days=10)
    await svc.update_prequal(contact_id, to="approved", expires_at=expired)
    status = await svc.get_prequal_status(contact_id)
    assert status.is_expired is True
    assert status.days_since_expiry is not None and status.days_since_expiry >= 10
    assert status.current_status == "expired"
