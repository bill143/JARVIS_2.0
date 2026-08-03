"""GovTribe sync heartbeat (Gap 1).

Runs every 15 minutes via Celery beat.  Reads the freshest
``last_synced_at`` from ``oe_nexus_precon_opportunity_cache``; if the most
recent successful sync is older than the configured threshold (default
60 minutes) it fires a three-channel alert:

* **Webhook** — every active OCERP webhook subscribed to
  ``govtribe.sync.stale`` (or ``*``) is dispatched via
  :class:`WebhookService.dispatch_event`, which logs a delivery row in
  ``oe_integrations_delivery``.
* **Email** — sent to the address in ``GOVTRIBE_ALERT_EMAIL`` (default
  ``bill@oneillcontractors.com``) through OCERP's
  :func:`get_email_service`.
* **In-app banner** — a ``Notification`` row is written for every user with
  ``role == 'precon_executive'`` (or admin) so the red banner appears at
  the top of NEXUS the next time they refresh.

Each channel is wrapped in its own try/except so a single downed
integration doesn't suppress the others.

The Celery decorator is applied opportunistically — when Celery isn't on the
Python path (test rigs, lightweight tooling) the module still imports cleanly
and :func:`run_heartbeat` remains directly callable.
"""

import logging
import os
from datetime import UTC, datetime
from typing import Any

from app.modules.precon.repository import OpportunityCacheRepository
from app.modules.precon.schemas import HeartbeatStatus

logger = logging.getLogger(__name__)

# ── Configuration ─────────────────────────────────────────────────────────

#: Minutes since last successful sync before we consider the upstream stale.
STALE_THRESHOLD_MINUTES: int = 60

#: Cron interval for the beat task (matches T07-01).
BEAT_INTERVAL_SECONDS: int = 15 * 60

#: Webhook event-type emitted on stale detection.
ALERT_EVENT_TYPE: str = "govtribe.sync.stale"

#: Default recipient for the stale-sync email — overridden by env var.
DEFAULT_ALERT_EMAIL: str = "bill@oneillcontractors.com"

#: Roles whose owners should receive the in-app banner notification.
ALERT_ROLES: tuple[str, ...] = ("precon_executive", "precon_bd_manager", "admin")


# ── Task body (sync-friendly so it can run under any executor) ────────────


async def run_heartbeat(session_factory: Any | None = None) -> HeartbeatStatus:
    """Check sync freshness and fire alerts when stale.

    ``session_factory`` is an async session callable (e.g.
    ``app.database.async_session_factory``); when omitted we try to import the
    OCERP factory lazily so unit tests can pass a fake.
    """
    factory = session_factory or _default_session_factory()
    if factory is None:
        logger.warning("Heartbeat: no session factory available; skipping run")
        return HeartbeatStatus(status="ok", alert_fired=False)

    async with factory() as session:
        repo = OpportunityCacheRepository(session)
        latest = await repo.latest_sync()
        now = datetime.now(UTC)
        if latest is None:
            return HeartbeatStatus(status="ok", last_synced_at=None, alert_fired=False)
        if latest.tzinfo is None:
            latest = latest.replace(tzinfo=UTC)
        delta_minutes = int((now - latest).total_seconds() // 60)
        if delta_minutes <= STALE_THRESHOLD_MINUTES:
            return HeartbeatStatus(
                status="ok",
                last_synced_at=latest,
                minutes_since_sync=delta_minutes,
                alert_fired=False,
            )

        await _fire_stale_alert(
            session=session,
            latest=latest,
            minutes_since=delta_minutes,
        )
        return HeartbeatStatus(
            status="stale",
            last_synced_at=latest,
            minutes_since_sync=delta_minutes,
            alert_fired=True,
        )


# ── Alert routing ─────────────────────────────────────────────────────────


async def _fire_stale_alert(
    *,
    session: Any,
    latest: datetime,
    minutes_since: int,
) -> None:
    """Fan out the stale-sync alert across webhook + email + in-app banner.

    Each channel is wrapped in its own try/except so a single downed
    integration doesn't suppress the others.  Channels that aren't wired
    yet log at DEBUG so an operator running with partial OCERP infra
    doesn't see noisy WARN spam.
    """
    payload = {
        "event_type": ALERT_EVENT_TYPE,
        "minutes_since_sync": minutes_since,
        "last_synced_at": latest.isoformat(),
        "threshold_minutes": STALE_THRESHOLD_MINUTES,
    }

    await _route_webhook(session, payload)
    await _route_email(payload)
    await _route_in_app_banner(session, payload)
    await _publish_event_bus(payload)


async def _route_webhook(session: Any, payload: dict[str, Any]) -> None:
    """Dispatch via OCERP's WebhookService — writes oe_integrations_delivery rows."""
    try:
        from app.modules.integrations.service import WebhookService  # type: ignore[import-not-found]

        svc = WebhookService(session)
        await svc.dispatch_event(event_type=ALERT_EVENT_TYPE, payload=payload)
    except Exception:
        logger.debug("Heartbeat: WebhookService dispatch failed", exc_info=True)


async def _route_email(payload: dict[str, Any]) -> None:
    """Send the stale-sync email via OCERP's get_email_service.

    Uses the EmailService.send seam directly — there's no purpose-built
    template helper for this alert yet, so we assemble a minimal HTML body
    inline rather than introduce a one-shot template module.
    """
    try:
        from app.core.email.base import EmailMessage  # type: ignore[import-not-found]
        from app.core.email.service import get_email_service  # type: ignore[import-not-found]
    except Exception:
        logger.debug("Heartbeat: email service module unavailable", exc_info=True)
        return

    recipient = os.environ.get("GOVTRIBE_ALERT_EMAIL", DEFAULT_ALERT_EMAIL)
    minutes = payload.get("minutes_since_sync", "?")
    last = payload.get("last_synced_at", "unknown")
    threshold = payload.get("threshold_minutes", STALE_THRESHOLD_MINUTES)

    subject = f"[NEXUS Precon] GovTribe sync stale — {minutes} minutes since last successful pull"
    html = (
        "<p>The NEXUS Precon GovTribe sync has not refreshed within the "
        f"{threshold}-minute threshold.</p>"
        f"<p><b>Minutes since last sync:</b> {minutes}<br/>"
        f"<b>Last successful sync:</b> {last}</p>"
        "<p>Federal opportunity intake is paused until the heartbeat clears. "
        "Check the NEXUS dashboard or the Celery worker logs for upstream "
        "transport errors.</p>"
    )

    try:
        service = get_email_service()
        message = EmailMessage(
            to=recipient,
            subject=subject,
            html_body=html,
            tags=["precon", "govtribe", "stale_sync_alert"],
        )
        await service.send(message)
    except Exception:
        logger.debug("Heartbeat: email send failed", exc_info=True)


async def _route_in_app_banner(session: Any, payload: dict[str, Any]) -> None:
    """Notify every user holding a Precon role via OCERP's NotificationService.

    The user query is intentionally narrow (role IN ALERT_ROLES) so we don't
    spam every user.  When the User model isn't on the import path the call
    is a no-op (test rigs without the OCERP stack).
    """
    try:
        from sqlalchemy import select

        from app.modules.notifications.service import NotificationService  # type: ignore[import-not-found]
        from app.modules.users.models import User  # type: ignore[import-not-found]
    except Exception:
        logger.debug("Heartbeat: notifications / users module unavailable", exc_info=True)
        return

    try:
        stmt = select(User.id).where(User.role.in_(ALERT_ROLES))
        result = await session.execute(stmt)
        user_ids = [row[0] for row in result.all()]
    except Exception:
        logger.debug("Heartbeat: user lookup failed", exc_info=True)
        return

    if not user_ids:
        logger.debug("Heartbeat: no users with Precon roles to notify")
        return

    notif_svc = NotificationService(session)
    minutes = payload.get("minutes_since_sync")
    for uid in user_ids:
        try:
            await notif_svc.create(
                user_id=uid,
                notification_type="precon.govtribe.stale_sync",
                title_key="precon.govtribe.stale_sync.title",
                body_key="precon.govtribe.stale_sync.body",
                body_context={"minutes_since_sync": minutes},
                action_url="/precon/dashboard",
                metadata={"event_type": ALERT_EVENT_TYPE, **payload},
            )
        except Exception:
            logger.debug("Heartbeat: notification create failed for %s", uid, exc_info=True)


async def _publish_event_bus(payload: dict[str, Any]) -> None:
    """Best-effort event-bus emit so other modules can subscribe."""
    try:
        from app.core.events import event_bus  # type: ignore[import-not-found]

        await event_bus.publish(ALERT_EVENT_TYPE, payload, source_module="oe_nexus_precon")
    except Exception:
        logger.debug("Heartbeat: event bus unavailable", exc_info=True)


# ── Optional Celery binding ───────────────────────────────────────────────


def _default_session_factory() -> Any | None:
    try:
        from app.database import async_session_factory  # type: ignore[import-not-found]

        return async_session_factory
    except Exception:
        return None


def _bind_to_celery() -> None:
    """Register the heartbeat task with the project's Celery app, if present."""
    try:
        from app.core.celery_app import celery_app  # type: ignore[import-not-found]
    except Exception:
        return
    try:
        from celery.schedules import schedule  # type: ignore[import-not-found]
    except Exception:
        return

    @celery_app.task(name="nexus_precon.heartbeat")
    def _task() -> dict:
        import asyncio

        return asyncio.get_event_loop().run_until_complete(run_heartbeat()).model_dump()

    existing_schedule = getattr(celery_app.conf, "beat_schedule", None) or {}
    celery_app.conf.beat_schedule = {
        **existing_schedule,
        "nexus_precon.heartbeat": {
            "task": "nexus_precon.heartbeat",
            "schedule": schedule(BEAT_INTERVAL_SECONDS),
        },
    }


_bind_to_celery()
