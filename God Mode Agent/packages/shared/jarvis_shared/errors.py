"""Typed error hierarchy surfaced as structured API errors."""

from __future__ import annotations


class JarvisError(Exception):
    code = "JARVIS_ERROR"
    http_status = 400

    def __init__(self, message: str = "", *, code: str | None = None):
        super().__init__(message or self.__class__.__name__)
        if code:
            self.code = code
        self.message = message or self.__class__.__name__


class ProviderUnavailable(JarvisError):
    code = "PROVIDER_UNAVAILABLE"
    http_status = 503


class AllProvidersFailed(JarvisError):
    code = "ALL_PROVIDERS_FAILED"
    http_status = 502


class ToolNotAllowed(JarvisError):
    code = "TOOL_NOT_ALLOWED"
    http_status = 403


class ToolValidationError(JarvisError):
    code = "TOOL_VALIDATION_ERROR"
    http_status = 422


class SandboxTimeout(JarvisError):
    code = "SANDBOX_TIMEOUT"
    http_status = 408


class UnsafePath(JarvisError):
    code = "UNSAFE_PATH"
    http_status = 403


class RateLimited(JarvisError):
    code = "RATE_LIMITED"
    http_status = 429


# --- Phase 2 additions ---


class AuthRequired(JarvisError):
    code = "AUTH_REQUIRED"
    http_status = 401


class AuthError(JarvisError):
    code = "AUTH_INVALID"
    http_status = 401


class AuthLocked(JarvisError):
    code = "AUTH_LOCKED"
    http_status = 429


class ForbiddenError(JarvisError):
    code = "FORBIDDEN"
    http_status = 403


class PolicyDenied(JarvisError):
    code = "POLICY_DENIED"
    http_status = 403


class PromptInjectionBlocked(JarvisError):
    code = "PROMPT_INJECTION_BLOCKED"
    http_status = 403


class IdempotencyConflict(JarvisError):
    code = "IDEMPOTENCY_CONFLICT"
    http_status = 409


class PayloadTooLarge(JarvisError):
    code = "PAYLOAD_TOO_LARGE"
    http_status = 413


class NotFound(JarvisError):
    code = "NOT_FOUND"
    http_status = 404
