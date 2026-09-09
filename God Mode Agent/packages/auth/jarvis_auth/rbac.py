"""RBAC roles and the request Principal."""

from __future__ import annotations

from dataclasses import dataclass, field

from jarvis_shared.errors import ForbiddenError

# Ordered by privilege (higher index = more privilege).
ROLES = ["readonly", "user", "operator", "admin"]
_RANK = {role: i for i, role in enumerate(ROLES)}


@dataclass
class Principal:
    user_id: str
    username: str
    role: str = "user"
    tenant: str = "default"
    auth_type: str = "jwt"  # jwt | apikey | dev-bypass
    scopes: list[str] = field(default_factory=list)

    @property
    def is_authenticated(self) -> bool:
        return bool(self.user_id)


def role_at_least(role: str, minimum: str) -> bool:
    return _RANK.get(role, -1) >= _RANK.get(minimum, 999)


def require_role(principal: Principal, minimum: str) -> None:
    if not role_at_least(principal.role, minimum):
        raise ForbiddenError(f"requires role '{minimum}' or higher (have '{principal.role}')")
