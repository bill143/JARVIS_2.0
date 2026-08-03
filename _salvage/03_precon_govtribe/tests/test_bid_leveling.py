"""Suite 5 — bid leveling engine (T05-01 .. T05-07)."""

import uuid
from decimal import Decimal
from unittest.mock import patch

import pytest

from app.modules.precon.services.bid_leveling import BidLevelingService

pytestmark = pytest.mark.asyncio


# ── Fake bids ─────────────────────────────────────────────────────────────


def _bid(amount: str, contact_id: uuid.UUID | None = None):
    """Build a minimal bid stand-in with attributes the leveling engine reads."""
    stub = type("BidStub", (), {})()
    stub.id = uuid.uuid4()
    stub.total_amount = amount
    stub.contact_id = contact_id or uuid.uuid4()
    return stub


# ── T05-01: 3 bids → 3 normalized snapshots ───────────────────────────────


async def test_level_three_bids_creates_three_snapshots(session, package_id):
    bids = [_bid("1000.00"), _bid("1500.00"), _bid("1250.00")]

    async def _fake_load(self, _pkg):  # noqa: ARG001
        return bids

    with patch.object(BidLevelingService, "_load_bids", _fake_load):
        svc = BidLevelingService(session)
        snapshots = await svc.level_bids(package_id)

    assert len(snapshots) == 3
    amounts = sorted(s.adjusted_amount for s in snapshots)
    assert amounts == [Decimal("1000.0000"), Decimal("1250.0000"), Decimal("1500.0000")]


# ── T05-02: apply_adjustment appends entry, updates adjusted_amount ───────


async def test_apply_adjustment_updates_adjusted_amount(session, package_id):
    bids = [_bid("1000.00")]

    async def _fake_load(self, _pkg):  # noqa: ARG001
        return bids

    with patch.object(BidLevelingService, "_load_bids", _fake_load):
        svc = BidLevelingService(session)
        snapshots = await svc.level_bids(package_id)
        snapshot = snapshots[0]
        updated = await svc.apply_adjustment(
            snapshot.id, amount=Decimal("500"), reason="scope gap"
        )

    assert updated.adjusted_amount == Decimal("1500.0000")
    assert len(updated.adjustments) == 1
    assert updated.adjustments[0]["reason"] == "scope gap"


# ── T05-03: snapshot row populated with leveled_by / leveled_at ───────────


async def test_snapshot_records_leveled_by_and_at(session, package_id, user_id):
    bids = [_bid("999.00")]

    async def _fake_load(self, _pkg):  # noqa: ARG001
        return bids

    with patch.object(BidLevelingService, "_load_bids", _fake_load):
        svc = BidLevelingService(session)
        snapshots = await svc.level_bids(package_id, leveled_by=user_id)

    assert snapshots[0].leveled_by == user_id
    assert snapshots[0].leveled_at is not None


# ── T05-04: repository exposes no delete method ───────────────────────────


def test_bid_level_repository_is_append_only_for_deletes():
    from app.modules.precon.repository import BidLevelRepository

    members = {name for name in dir(BidLevelRepository) if not name.startswith("_")}
    assert "delete" not in members, "BidLevelRepository must not expose delete()"


# ── T05-05: mark_winner sets winner; clears every other for the package ───


async def test_mark_winner_sets_single_winner(session, package_id):
    bids = [_bid("1000"), _bid("1500"), _bid("1250")]

    async def _fake_load(self, _pkg):  # noqa: ARG001
        return bids

    with patch.object(BidLevelingService, "_load_bids", _fake_load):
        svc = BidLevelingService(session)
        snapshots = await svc.level_bids(package_id)
        await svc.mark_winner(snapshots[0].id)

    refreshed = await svc.list_for_package(package_id)
    winners = [s for s in refreshed if s.is_winner]
    losers = [s for s in refreshed if not s.is_winner]
    assert len(winners) == 1
    assert winners[0].id == snapshots[0].id
    assert len(losers) == 2


# ── T05-06: mark_winner re-flip flips previous winner ─────────────────────


async def test_mark_winner_reflip(session, package_id):
    bids = [_bid("1000"), _bid("2000"), _bid("3000")]

    async def _fake_load(self, _pkg):  # noqa: ARG001
        return bids

    with patch.object(BidLevelingService, "_load_bids", _fake_load):
        svc = BidLevelingService(session)
        snapshots = await svc.level_bids(package_id)
        await svc.mark_winner(snapshots[0].id)
        await svc.mark_winner(snapshots[1].id)

    refreshed = await svc.list_for_package(package_id)
    winners = [s for s in refreshed if s.is_winner]
    assert len(winners) == 1
    assert winners[0].id == snapshots[1].id


# ── T05-07: empty package returns empty list, no error ────────────────────


async def test_empty_package_returns_empty_list(session, package_id):
    async def _fake_load(self, _pkg):  # noqa: ARG001
        return []

    with patch.object(BidLevelingService, "_load_bids", _fake_load):
        svc = BidLevelingService(session)
        result = await svc.level_bids(package_id)

    assert result == []
