"""Precon services package.

Exports the custom exception types so callers (router, tests) can import them
from a single location.
"""


class PreconError(Exception):
    """Base class for all Precon-specific errors."""


class ForbiddenTransitionError(PreconError):
    """Raised when the service layer rejects an illegal state transition.

    Used by both ``pipeline_service`` (opportunity stage machine) and
    ``rfq_invitation_service`` (8-state invitation lifecycle).
    """


class InvalidPhaseError(PreconError):
    """Raised by ``award_and_activate`` when the project is not in 'bidding'."""


class GovTribeConnectionError(PreconError):
    """Raised by ``govtribe_adapter`` when the upstream MCP bridge is unreachable."""


__all__ = [
    "PreconError",
    "ForbiddenTransitionError",
    "InvalidPhaseError",
    "GovTribeConnectionError",
]
