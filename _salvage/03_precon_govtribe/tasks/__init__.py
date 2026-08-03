"""Precon background tasks (Celery beat).

Exposes the heartbeat task that monitors GovTribe sync freshness and fires
alerts when the upstream goes stale.
"""
