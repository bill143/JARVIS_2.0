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

    app.include_router(router)
