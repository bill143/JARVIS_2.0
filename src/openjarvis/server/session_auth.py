"""Session-auth middleware + login gateway for the OpenJarvis server.

When enabled, the middleware fails closed: every route except the login flow and
health check requires a valid session cookie. Browser navigations without a
session are redirected to ``/login``; API/JSON clients get a 401.
"""

from __future__ import annotations

from typing import Iterable

from fastapi import APIRouter, Form, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from starlette.middleware.base import BaseHTTPMiddleware

from openjarvis.server.auth import AuthStore

# Routes always reachable without a session (the login flow + health probe).
_DEFAULT_EXEMPT = ("/login", "/logout", "/health", "/favicon.ico")


def _wants_html(request: Request) -> bool:
    """Heuristic: a browser navigation (Accept: text/html) vs an API client."""
    return "text/html" in request.headers.get("accept", "").lower()


class SessionAuthMiddleware(BaseHTTPMiddleware):
    """Blocks unauthenticated access to every non-exempt route (fail-closed)."""

    def __init__(
        self,
        app,  # noqa: ANN001
        *,
        store: AuthStore,
        cookie_name: str = "oj_session",
        exempt: Iterable[str] = _DEFAULT_EXEMPT,
    ) -> None:
        super().__init__(app)
        self._store = store
        self._cookie = cookie_name
        self._exempt = tuple(exempt)

    async def dispatch(self, request: Request, call_next):  # noqa: ANN001
        path = request.url.path
        if any(path == p or path.startswith(p + "/") for p in self._exempt):
            return await call_next(request)

        username = self._store.validate_session(request.cookies.get(self._cookie))
        if username is None:
            if _wants_html(request):
                return RedirectResponse("/login", status_code=303)
            return JSONResponse({"detail": "Authentication required"}, status_code=401)

        request.state.user = username
        return await call_next(request)


_LOGIN_PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>JARVIS - Sign in</title><style>
:root{color-scheme:dark}
body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
background:#04070d;color:#eaf7ff;font-family:system-ui,sans-serif}
.card{width:min(360px,92vw);background:#0a1420;border:1px solid #1d3550;
border-radius:14px;padding:28px 26px;box-shadow:0 0 44px rgba(60,180,240,.15)}
h1{font-size:18px;letter-spacing:2px;margin:0 0 4px}
.sub{color:#7fa8c9;font-size:13px;margin-bottom:18px}
label{display:block;font-size:12px;color:#9fc4e0;margin:12px 0 4px}
input{width:100%;box-sizing:border-box;padding:10px 12px;border-radius:9px;
border:1px solid #22415f;background:#071019;color:#eaf7ff;font-size:14px}
button{margin-top:18px;width:100%;padding:11px;border-radius:9px;
border:1px solid #35b4f0;background:#0e2438;color:#bfe6ff;font-weight:600;
cursor:pointer}
.err{color:#f6a3a3;font-size:13px;margin-top:12px;min-height:16px}</style></head>
<body><form class="card" method="post" action="/login">
<h1>J.A.R.V.I.S.</h1><div class="sub">Sign in to continue</div>
<label for="u">Username</label>
<input id="u" name="username" autocomplete="username" autofocus>
<label for="p">Password</label>
<input id="p" name="password" type="password" autocomplete="current-password">
<button type="submit">Sign in</button>
<div class="err">%ERROR%</div></form></body></html>"""


def _login_html(error: str = "") -> str:
    return _LOGIN_PAGE.replace("%ERROR%", error)


def create_auth_router(
    store: AuthStore,
    *,
    cookie_name: str = "oj_session",
    ttl_hours: int = 12,
    cookie_secure: bool = True,
) -> APIRouter:
    """Router with the login page, login/logout handlers, and session cookies."""
    router = APIRouter()

    @router.get("/login", response_class=HTMLResponse)
    async def login_page() -> HTMLResponse:
        return HTMLResponse(_login_html())

    @router.post("/login")
    async def login(username: str = Form(""), password: str = Form("")):
        if not store.verify_user(username, password):
            # Same status for unknown user and bad password (no user enumeration).
            return HTMLResponse(
                _login_html("Invalid username or password."), status_code=401
            )
        token = store.create_session(username, ttl_hours=ttl_hours)
        resp = RedirectResponse("/", status_code=303)
        resp.set_cookie(
            cookie_name,
            token,
            httponly=True,
            secure=cookie_secure,
            samesite="lax",
            max_age=ttl_hours * 3600,
            path="/",
        )
        return resp

    @router.post("/logout")
    async def logout(request: Request):
        token = request.cookies.get(cookie_name)
        if token:
            store.delete_session(token)
        resp = RedirectResponse("/login", status_code=303)
        resp.delete_cookie(cookie_name, path="/")
        return resp

    return router


__all__ = ["SessionAuthMiddleware", "create_auth_router"]
