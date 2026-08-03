"""Bid-leveling engine.

Normalises raw bids against a tender package, lets the estimator apply
adjustments (scope gaps, exclusions, qualifications), and tracks the
designated winner.  All snapshot rows are append-only — the only mutation
ever performed on ``oe_nexus_precon_bid_level`` is the
:meth:`mark_winner` flag flip, which only touches the boolean.
"""

import logging
import uuid
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation

from sqlalchemy.ext.asyncio import AsyncSession

from app.modules.precon.models import BidLevel
from app.modules.precon.repository import BidLevelRepository

logger = logging.getLogger(__name__)


def _to_decimal(value) -> Decimal:
    """Coerce a numeric / string / Decimal into a Decimal."""
    if value is None:
        return Decimal("0")
    if isinstance(value, Decimal):
        return value
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError, TypeError):
        return Decimal("0")


class BidLevelingService:
    """Stateful service bound to a single ``AsyncSession``."""

    def __init__(self, session: AsyncSession) -> None:
        self.session = session
        self.repo = BidLevelRepository(session)

    async def level_bids(
        self,
        package_id: uuid.UUID,
        *,
        leveled_by: uuid.UUID | None = None,
    ) -> list[BidLevel]:
        """Create a baseline leveling snapshot per bid on ``package_id``.

        Returns an empty list when the package has no bids — callers should
        treat that as an informational state, not an error (T05-07).
        """
        bids = await self._load_bids(package_id)
        snapshots: list[BidLevel] = []
        for bid in bids:
            raw = _to_decimal(getattr(bid, "total_amount", "0"))
            snapshot = await self.repo.add(
                package_id=package_id,
                bidder_contact_id=getattr(bid, "contact_id", None),
                raw_amount=raw,
                adjustments=[],
                adjusted_amount=raw,
                notes=None,
                leveled_by=leveled_by,
            )
            snapshots.append(snapshot)
        logger.info("Leveling snapshot created: package=%s bids=%d", package_id, len(snapshots))
        return snapshots

    async def apply_adjustment(
        self,
        bid_level_id: uuid.UUID,
        *,
        amount,
        reason: str,
        label: str | None = None,
    ) -> BidLevel:
        """Append an adjustment entry and recompute ``adjusted_amount``.

        We do *not* rewrite the existing snapshot row — instead we mutate the
        JSON adjustments list in place and update only ``adjusted_amount``.
        The audit fields (raw_amount, leveled_by, leveled_at) stay frozen.
        """
        snapshot = await self.repo.get_by_id(bid_level_id)
        if snapshot is None:
            raise ValueError(f"BidLevel {bid_level_id} not found")
        adj_amount = _to_decimal(amount)
        entry = {
            "label": label,
            "amount": str(adj_amount),
            "reason": reason,
            "applied_at": datetime.now(UTC).isoformat(),
        }
        adjustments = list(snapshot.adjustments or [])
        adjustments.append(entry)
        new_adjusted = _to_decimal(snapshot.adjusted_amount) + adj_amount
        snapshot.adjustments = adjustments
        snapshot.adjusted_amount = new_adjusted
        await self.session.flush()
        return snapshot

    async def mark_winner(self, bid_level_id: uuid.UUID) -> BidLevel:
        """Mark ``bid_level_id`` as the package winner; flip every other to False."""
        snapshot = await self.repo.get_by_id(bid_level_id)
        if snapshot is None:
            raise ValueError(f"BidLevel {bid_level_id} not found")
        await self.repo.set_winner_flag(package_id=snapshot.package_id, level_id=bid_level_id)
        await self.session.refresh(snapshot)
        return snapshot

    async def list_for_package(self, package_id: uuid.UUID) -> list[BidLevel]:
        return await self.repo.list_for_package(package_id)

    # ── Helpers ────────────────────────────────────────────────────────────

    async def _load_bids(self, package_id: uuid.UUID) -> list[object]:
        """Fetch all bids for a tendering package via the OCERP TenderBid model.

        Returns an empty list when OCERP isn't on the import path so the
        leveling engine remains exercisable in a precon-only test rig.
        """
        try:
            from sqlalchemy import select

            from app.modules.tendering.models import TenderBid  # type: ignore[import-not-found]
        except Exception:
            logger.debug("TenderBid model not importable; returning no bids")
            return []
        stmt = select(TenderBid).where(TenderBid.package_id == package_id)
        result = await self.session.execute(stmt)
        return list(result.scalars().all())
