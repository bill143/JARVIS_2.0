"""Secret detection, masking, and environment-reference resolution.

A single place that decides which config keys hold sensitive values and knows
how to:

* **mask** them before they reach an API response, the dashboard, a CLI table,
  or a log line (:func:`redact_mapping`), and
* **externalize** them out of the database into environment variables, storing
  only a ``${VAR}`` reference on disk (:func:`externalize_secrets`), then
  **resolve** those references back to real values for server-internal use
  (:func:`resolve_mapping`).

Everything here is stdlib-only and side-effect free except where a caller
supplies its own ``setter`` for persistence.
"""

from __future__ import annotations

import os
import re
from typing import Any, Callable

# Substrings that mark a config key as holding a secret value.
_SECRET_KEY_SUBSTRINGS = (
    "password",
    "passwd",
    "secret",
    "token",
    "api_key",
    "apikey",
    "api_secret",
    "access_key",
    "private_key",
    "client_secret",
    "app_secret",
    "signing_secret",
    "webhook_secret",
    "credential",
    "auth_token",
)

# Keys that contain a secret-ish substring but are not themselves secrets
# (counters, sizes, booleans). Matched exactly against the lower-cased key.
_SECRET_KEY_ALLOWLIST = frozenset(
    {
        "token_count",
        "max_tokens",
        "tokens",
        "total_tokens",
        "input_tokens",
        "output_tokens",
        "token_usage",
        "n_tokens",
    }
)

# A stored reference to an environment variable, e.g. "${OJ_BINDING_AB12_TOKEN}".
_ENV_REF = re.compile(r"^\$\{([A-Za-z_][A-Za-z0-9_]*)\}$")


def is_secret_key(key: str) -> bool:
    """Return True if a config key name looks like it holds a secret value."""
    if not isinstance(key, str):
        return False
    k = key.lower()
    if k in _SECRET_KEY_ALLOWLIST:
        return False
    return any(sub in k for sub in _SECRET_KEY_SUBSTRINGS)


def env_ref(var_name: str) -> str:
    """Format ``var_name`` as a ``${VAR}`` reference for on-disk storage."""
    return "${" + var_name + "}"


def is_env_ref(value: Any) -> bool:
    """True if ``value`` is a ``${VAR}`` environment reference string."""
    return isinstance(value, str) and _ENV_REF.match(value) is not None


def mask_secret(value: Any) -> str:
    """Mask a secret for display: keep a hint, hide the middle.

    ``"${VAR}"`` references render as a fixed placeholder (they are not raw
    secrets). Short values are fully masked; longer values keep the first and
    last two characters (``"sk-abcd1234" -> "sk••••••34"``).
    """
    if value is None:
        return ""
    if is_env_ref(value):
        return "••••• (from env)"
    s = str(value)
    if not s:
        return ""
    if len(s) <= 4:
        return "•" * len(s)
    return f"{s[:2]}{'•' * 6}{s[-2:]}"


def redact_mapping(obj: Any) -> Any:
    """Deep-copy ``obj`` with every secret-keyed scalar masked.

    Recurses through dicts and lists; leaves non-secret values untouched. The
    input is never mutated, so it is safe to redact a value on its way out to a
    client while the original stays usable internally.
    """
    if isinstance(obj, dict):
        out: dict[Any, Any] = {}
        for k, v in obj.items():
            if is_secret_key(str(k)) and isinstance(v, (str, int, float, bool)):
                out[k] = mask_secret(v)
            else:
                out[k] = redact_mapping(v)
        return out
    if isinstance(obj, list):
        return [redact_mapping(v) for v in obj]
    return obj


def externalize_secrets(
    config: dict[str, Any],
    *,
    var_prefix: str,
    setter: Callable[[str, str], None],
) -> dict[str, Any]:
    """Return a copy of ``config`` with secret values moved into the environment.

    For each secret-keyed, non-empty string value that is not already a
    ``${VAR}`` reference, a deterministic env-var name is derived from
    ``var_prefix`` and the key, ``setter(var_name, value)`` is called to persist
    the real value out-of-band, and the stored config keeps only the
    ``${VAR}`` reference. Non-secret and already-externalized values pass
    through unchanged, so this is safe to apply idempotently.
    """
    out: dict[str, Any] = {}
    for key, value in config.items():
        if (
            is_secret_key(str(key))
            and isinstance(value, str)
            and value
            and not is_env_ref(value)
        ):
            raw = f"{var_prefix}_{key}".upper()
            var_name = re.sub(r"[^A-Z0-9_]", "_", raw)
            setter(var_name, value)
            out[key] = env_ref(var_name)
        else:
            out[key] = value
    return out


def resolve_value(value: Any) -> Any:
    """Resolve a single ``${VAR}`` reference from the environment, else passthrough."""
    if isinstance(value, str):
        m = _ENV_REF.match(value)
        if m:
            return os.environ.get(m.group(1), "")
    return value


def resolve_mapping(obj: Any) -> Any:
    """Deep-copy ``obj`` with every ``${VAR}`` reference resolved from the env."""
    if isinstance(obj, dict):
        return {k: resolve_mapping(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [resolve_mapping(v) for v in obj]
    return resolve_value(obj)


__all__ = [
    "is_secret_key",
    "env_ref",
    "is_env_ref",
    "mask_secret",
    "redact_mapping",
    "externalize_secrets",
    "resolve_value",
    "resolve_mapping",
]
