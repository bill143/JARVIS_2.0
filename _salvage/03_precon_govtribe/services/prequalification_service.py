"""Prequalification service.

Writes append-only audit rows to ``oe_nexus_precon_prequalification_event``
and exposes a read API for the current status (with expiry detection).

The contact's own ``prequalification_status`` / ``qualified_until`` columns on
``oe_contacts_contact`` remain the live source of truth; this module owns the
audit history.  We update both on every state change so the contact directory
view and the audit log stay aligned.
"""

import logging
import uuid
from datetime import UTC, datetime

from sqlalchemy.ext.asyncio import AsyncSession

from app.modules.precon.models import PrequalificationEvent
from app.modules.precon.repository import PrequalificationRepository
from app.modules.precon.schemas import PREQUAL_STATES, PrequalStatusResponse

logger = logging.getLogger(__name__)


def _validate_target(state: str) -> None:
    if state not in PREQUAL_STATES:
        raise ValueError(
            f"Unknown prequalification status {state!r}; allowed: {sorted(PREQUAL_STATES)}"
        )


class PrequalificationService:
    """Stateful service bound to one ``AsyncSession``."""

    def __init__(self, session: AsyncSession) -> None:
        self.session = session
        self.repo = PrequalificationRepository(session)

    async def update_prequal(
        self,
        contact_id: uuid.UUID,
        *,
        to: str,
        reason: str | None = None,
        expires_at: datetime | None = None,
        changed_by: uuid.UUID | None = None,
    ) -> PrequalificationEvent:
        """Record a prequalification status change for ``contact_id``.

        Reads the previous status from the contact directory when available;
        falls back to the most recent audit row when the OCERP Contact model
        isn't on the import path.
        """
        _validate_target(to)
        previous = await self._read_current_status(contact_id)
        event = await self.repo.add(
            contact_id=contact_id,
            from_status=previous,
            to_status=to,
            changed_by=changed_by,
            reason=reason,
            expires_at=expires_at,
        )
        await self._mirror_to_contact(contact_id, status=to, expires_at=expires_at)
        logger.info("Prequal change %s: %s -> %s", contact_id, previous, to)
        return event

    async def get_prequal_status(self, contact_id: uuid.UUID) -> PrequalStatusResponse:
        """Return the current prequal status with expiry information.

        ``is_expired`` is computed from the most recent event's ``expires_at``
        column.  When expiry is in the past, ``days_since_expiry`` is the
        positive whole-day delta; otherwise ``None``.
        """
        latest = await self.repo.latest_for_contact(contact_id)
        if latest is None:
            return PrequalStatusResponse(contact_id=contact_id, current_status=None)
        is_expired = False
        days_since = None
        if latest.expires_at is not None:
            expires = latest.expires_at
            now = datetime.now(UTC)
            if expires.tzinfo is None:
                # Compare naive-to-naive when the stored value lost its tz on
                # the SQLite round-trip — both are conceptually UTC.
                now = now.replace(tzinfo=None)
            if expires < now:
                is_expired = True
                days_since = max(0, (now - expires).days)
        return PrequalStatusResponse(
            contact_id=contact_id,
            current_status=latest.to_status if not is_expired else "expired",
            expires_at=latest.expires_at,
            is_expired=is_expired,
            days_since_expiry=days_since,
        )

    async def list_events(self, contact_id: uuid.UUID) -> list[PrequalificationEvent]:
        return await self.repo.list_for_contact(contact_id)

    # ── Helpers ────────────────────────────────────────────────────────────

    async def _read_current_status(self, contact_id: uuid.UUID) -> str | None:
        """Best-effort read of the contact's live prequalification status."""
        try:
            from app.modules.contacts.models import Contact  # type: ignore[import-not-found]

            contact = await self.session.get(Contact, contact_id)
            if contact is not None:
                return getattr(contact, "prequalification_status", None)
        except Exception:
            logger.debug("Contact model not importable; falling back to audit-log lookup")
        latest = await self.repo.latest_for_contact(contact_id)
        return latest.to_status if latest else None

    async def _mirror_to_contact(
        self,
        contact_id: uuid.UUID,
        *,
        status: str,
        expires_at: datetime | None,
    ) -> None:
        """Mirror the new status onto the OCERP Contact directory row.

        Best-effort: when the OCERP Contact model isn't loaded, we silently
        skip — the audit log row in our own table is the durable record.
        """
        try:
            from sqlalchemy import update as sa_update

            from app.modules.contacts.models import Contact  # type: ignore[import-not-found]
        except Exception:
            return
        values: dict[str, object] = {"prequalification_status": status}
        if expires_at is not None:
            values["qualified_until"] = expires_at.date().isoformat()
        stmt = sa_update(Contact).where(Contact.id == contact_id).values(**values)
        await self.session.execute(stmt)
        await self.session.flush()
