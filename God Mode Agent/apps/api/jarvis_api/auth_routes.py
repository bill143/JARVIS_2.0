"""Authentication + API-key routes."""

from __future__ import annotations

from fastapi import APIRouter, Request
from pydantic import BaseModel, Field

from jarvis_api.deps import require_min_role, resolve_principal
from jarvis_auth.rbac import ROLES
from jarvis_shared.schemas import ok_envelope

router = APIRouter()


class RegisterBody(BaseModel):
    model_config = {"extra": "forbid"}
    username: str = Field(min_length=3, max_length=64)
    password: str = Field(min_length=8, max_length=256)
    role: str = Field(default="user")
    email: str = Field(default="", max_length=256)


class LoginBody(BaseModel):
    model_config = {"extra": "forbid"}
    username: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=256)


class RefreshBody(BaseModel):
    model_config = {"extra": "forbid"}
    refresh_token: str = Field(min_length=1)


class OAuthBody(BaseModel):
    model_config = {"extra": "forbid"}
    provider: str
    code: str


class ApiKeyBody(BaseModel):
    model_config = {"extra": "forbid"}
    name: str = Field(min_length=1, max_length=64)
    role: str = Field(default="user")
    scopes: str = Field(default="")


class ChangePasswordBody(BaseModel):
    model_config = {"extra": "forbid"}
    current_password: str = Field(min_length=1, max_length=256)
    new_password: str = Field(min_length=8, max_length=256)


class RoleBody(BaseModel):
    model_config = {"extra": "forbid"}
    role: str


def register_auth_routes(app):
    @router.post("/auth/register")
    async def register(body: RegisterBody, request: Request):
        # Only admins may create privileged accounts; anyone may self-register as 'user'.
        auth = request.app.state.jarvis.auth
        role = body.role if body.role in ROLES else "user"
        if role != "user":
            require_min_role(request, "admin")
        user = auth.register(body.username, body.password, role=role, email=body.email)
        request.app.state.jarvis.audit.record("auth", "register", actor=body.username, detail={"role": role})
        return ok_envelope({"id": user["id"], "username": user["username"], "role": user["role"]})

    @router.post("/auth/login")
    async def login(body: LoginBody, request: Request):
        auth = request.app.state.jarvis.auth
        tokens = auth.login(body.username, body.password)
        request.app.state.jarvis.audit.record("auth", "login", actor=body.username)
        return ok_envelope(tokens)

    @router.post("/auth/refresh")
    async def refresh(body: RefreshBody, request: Request):
        auth = request.app.state.jarvis.auth
        tokens = auth.refresh_tokens(body.refresh_token)
        return ok_envelope(tokens)

    @router.post("/auth/logout")
    async def logout(body: RefreshBody, request: Request):
        request.app.state.jarvis.auth.logout(body.refresh_token)
        return ok_envelope({"logged_out": True})

    @router.post("/auth/oauth")
    async def oauth(body: OAuthBody, request: Request):
        auth = request.app.state.jarvis.auth
        tokens = auth.oauth_login(body.provider, body.code)
        request.app.state.jarvis.audit.record("auth", "oauth_login", actor=tokens["user"]["username"], detail={"provider": body.provider})
        return ok_envelope(tokens)

    @router.get("/auth/me")
    async def me(request: Request):
        principal = resolve_principal(request)
        return ok_envelope({
            "user_id": principal.user_id, "username": principal.username,
            "role": principal.role, "tenant": principal.tenant, "auth_type": principal.auth_type,
        })

    @router.post("/apikeys")
    async def create_api_key(body: ApiKeyBody, request: Request):
        require_min_role(request, "admin")
        auth = request.app.state.jarvis.auth
        result = auth.create_api_key(body.name, role=body.role, scopes=body.scopes)
        request.app.state.jarvis.audit.record("auth", "apikey_create", actor="admin", detail={"name": body.name, "role": body.role})
        return ok_envelope(result)  # includes the raw key ONCE

    @router.get("/apikeys")
    async def list_api_keys(request: Request):
        require_min_role(request, "admin")
        return ok_envelope({"keys": request.app.state.jarvis.auth.api_keys.list_all()})

    @router.post("/apikeys/{key_id}/revoke")
    async def revoke_api_key(key_id: str, request: Request):
        p = require_min_role(request, "admin")
        auth = request.app.state.jarvis.auth
        meta = auth.api_keys.get_meta(key_id)
        if not meta:
            from jarvis_shared.errors import NotFound
            raise NotFound("api key not found")
        auth.api_keys.revoke(key_id)
        request.app.state.jarvis.audit.record("auth", "apikey_revoke", actor=p.username, detail={"key_id": key_id, "name": meta["name"]})
        return ok_envelope({"id": key_id, "revoked": True})

    # ---- self-service password ----

    @router.post("/auth/change-password")
    async def change_password(body: ChangePasswordBody, request: Request):
        p = resolve_principal(request)
        auth = request.app.state.jarvis.auth
        auth.change_password(p.user_id, body.current_password, body.new_password)
        request.app.state.jarvis.audit.record("auth", "password_change", actor=p.username)
        return ok_envelope({"changed": True, "note": "all sessions revoked — sign in again"})

    # ---- admin user management ----

    @router.get("/users")
    async def list_users(request: Request):
        require_min_role(request, "admin")
        return ok_envelope({"users": request.app.state.jarvis.auth.users.list_all()})

    @router.post("/users/{user_id}/role")
    async def set_user_role(user_id: str, body: RoleBody, request: Request):
        p = require_min_role(request, "admin")
        if body.role not in ROLES:
            from jarvis_shared.errors import JarvisError
            raise JarvisError(f"invalid role; expected one of {sorted(ROLES)}", code="INVALID_ROLE")
        user = request.app.state.jarvis.auth.set_user_role(user_id, body.role)
        request.app.state.jarvis.audit.record("auth", "role_change", actor=p.username, detail={"user_id": user_id, "role": body.role})
        return ok_envelope(user)

    @router.post("/users/{user_id}/disable")
    async def disable_user(user_id: str, request: Request):
        p = require_min_role(request, "admin")
        if p.user_id == user_id:
            from jarvis_shared.errors import ForbiddenError
            raise ForbiddenError("cannot disable your own account", code="SELF_DISABLE_FORBIDDEN")
        user = request.app.state.jarvis.auth.set_user_disabled(user_id, True)
        request.app.state.jarvis.audit.record("auth", "user_disable", actor=p.username, detail={"user_id": user_id})
        return ok_envelope(user)

    @router.post("/users/{user_id}/enable")
    async def enable_user(user_id: str, request: Request):
        p = require_min_role(request, "admin")
        user = request.app.state.jarvis.auth.set_user_disabled(user_id, False)
        request.app.state.jarvis.audit.record("auth", "user_enable", actor=p.username, detail={"user_id": user_id})
        return ok_envelope(user)

    @router.post("/users/{user_id}/reset-password")
    async def reset_user_password(user_id: str, request: Request):
        p = require_min_role(request, "admin")
        temp = request.app.state.jarvis.auth.admin_reset_password(user_id)
        request.app.state.jarvis.audit.record("auth", "password_reset", actor=p.username, detail={"user_id": user_id})
        # temp password is returned ONCE and never logged
        return ok_envelope({"user_id": user_id, "temporary_password": temp,
                            "note": "shown once — user must change it after signing in"})

    app.include_router(router)
