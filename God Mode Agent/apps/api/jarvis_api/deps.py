"""Request auth resolution, RBAC dependency, and principal-bound tool shim."""

from __future__ import annotations

from fastapi import Request

from jarvis_auth.rbac import Principal, require_role
from jarvis_policy.governed import ApprovalRequired
from jarvis_shared.errors import AuthError, AuthRequired, PolicyDenied
from jarvis_tools.registry import ToolResult


def resolve_principal(request: Request) -> Principal:
    """Resolve a Principal from Bearer JWT, X-API-Key, or dev bypass."""
    jarvis = request.app.state.jarvis
    auth = jarvis.auth
    settings = jarvis.settings

    header = request.headers.get("authorization", "")
    if header.lower().startswith("bearer "):
        return auth.principal_from_access(header[7:].strip())

    api_key = request.headers.get("x-api-key", "")
    if api_key:
        return auth.principal_from_api_key(api_key)

    if settings.allow_dev_auth_bypass:
        return auth.dev_bypass_principal()

    raise AuthRequired("authentication required: provide a Bearer token or X-API-Key")


def require_min_role(request: Request, minimum: str) -> Principal:
    principal = resolve_principal(request)
    require_role(principal, minimum)
    return principal


class PrincipalBoundTools:
    """Adapt GovernedToolRegistry to the plain ToolRegistry interface the agent
    loop expects: schemas() + execute(name, args, session_id) -> ToolResult.

    Policy denials / approval-required become error ToolResults so a chat turn
    degrades gracefully instead of crashing; the decision is still audited.
    """

    def __init__(self, governed, principal: Principal):
        self.governed = governed
        self.principal = principal

    def schemas(self) -> list[dict]:
        return self.governed.schemas()

    def names(self) -> list[str]:
        return self.governed.names()

    async def execute(self, name: str, arguments: dict, session_id: str = "default") -> ToolResult:
        try:
            result, _decision = await self.governed.execute(self.principal, name, arguments, session_id=session_id)
            return result
        except ApprovalRequired as exc:
            return ToolResult(tool=name, status="error",
                              output={"error": "approval_required", "approval_id": exc.approval_id, "reason": exc.message},
                              duration_ms=0.0)
        except PolicyDenied as exc:
            return ToolResult(tool=name, status="error", output={"error": "policy_denied", "reason": exc.message}, duration_ms=0.0)
        except AuthError as exc:
            return ToolResult(tool=name, status="error", output={"error": "auth", "reason": exc.message}, duration_ms=0.0)
