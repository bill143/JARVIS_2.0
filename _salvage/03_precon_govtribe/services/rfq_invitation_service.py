"""RFQ-invitation service.

Owns the 8-state invitation lifecycle (SC-04) and enforces the legal-transition
graph from the build contract.  Mutations go through
:class:`RFQInvitationRepository` so this file contains zero raw SQL.
"""

import logging
import uuid
from datetime import UTC, datetime

from sqlalchemy.ext.asyncio import AsyncSession

from app.modules.precon.models import RFQInvitation
from app.modules.precon.repository import RFQInvitationRepository
from app.modules.precon.schemas import INVITATION_STATES
from app.modules.precon.services import ForbiddenTransitionError

logger = logging.getLogger(__name__)


# ── State machine (pure data) ──────────────────────────────────────────────


_INVITATION_TRANSITIONS: dict[str, frozenset[str]] = {
    "INVITED":     frozenset({"VIEWED", "DECLINED", "IGNORED"}),
    "VIEWED":      frozenset({"ACCEPTED", "DECLINED", "IGNORED"}),
    "ACCEPTED":    frozenset({"SUBMITTED", "DECLINED"}),
    "SUBMITTED":   frozenset({"AWARDED", "NOT_AWARDED"}),
    # Terminal states.
    "DECLINED":    frozenset(),
    "IGNORED":     frozenset(),
    "AWARDED":     frozenset(),
    "NOT_AWARDED": frozenset(),
}


def allowed_transitions(from_state: str | None) -> frozenset[str]:
    """Return the legal next states from ``from_state``."""
    if from_state is None:
        return frozenset()
    return _INVITATION_TRANSITIONS.get(from_state, frozenset())


def _validate_target(state: str) -> None:
    if state not in INVITATION_STATES:
        raise ValueError(
            f"Unknown invitation state {state!r}; allowed: {sorted(INVITATION_STATES)}"
        )


# ── Service ────────────────────────────────────────────────────────────────


class RFQInvitationService:
    """Stateful service bound to a single ``AsyncSession`` per request."""

    def __init__(self, session: AsyncSession) -> None:
        self.session = session
        self.repo = RFQInvitationRepository(session)

    async def invite(
        self,
        *,
        rfq_id: uuid.UUID,
        contact_id: uuid.UUID,
        metadata: dict | None = None,
    ) -> RFQInvitation:
        """Create a fresh INVITED row for the (rfq, contact) pair.

        The repository's unique constraint on ``(rfq_id, contact_id)`` prevents
        duplicates at the DB layer; callers can choose to catch and treat as a
        no-op.
        """
        return await self.repo.add(rfq_id=rfq_id, contact_id=contact_id, metadata=metadata)

    async def transition(
        self,
        invitation_id: uuid.UUID,
        *,
        to: str,
    ) -> RFQInvitation:
        """Move an invitation to ``to``; updates timestamps as side-effects.

        * VIEWED transitions stamp ``last_viewed_at``.
        * Any non-VIEWED move stamps ``responded_at``.
        """
        _validate_target(to)
        invitation = await self.repo.get_by_id(invitation_id)
        if invitation is None:
            raise ValueError(f"Invitation {invitation_id} not found")
        legal = allowed_transitions(invitation.status)
        if to not in legal:
            raise ForbiddenTransitionError(
                f"Invitation transition {invitation.status!r} -> {to!r} is not permitted; "
                f"legal next states from {invitation.status!r}: {sorted(legal)}"
            )

        now = datetime.now(UTC)
        last_viewed_at = now if to == "VIEWED" else None
        responded_at = now if to != "VIEWED" else None

        await self.repo.update_status(
            invitation_id=invitation_id,
            new_status=to,
            last_viewed_at=last_viewed_at,
            responded_at=responded_at,
        )
        # Refresh the in-memory ORM object so callers see the new state.
        await self.session.refresh(invitation)
        logger.info("Invitation transition %s: %s", invitation_id, to)
        return invitation

    async def list_for_rfq(self, rfq_id: uuid.UUID) -> list[RFQInvitation]:
        return await self.repo.list_for_rfq(rfq_id)
