"""Tests for secret detection, masking, and env-reference resolution."""

from __future__ import annotations

from openjarvis.core.secrets import (
    env_ref,
    externalize_secrets,
    is_env_ref,
    is_secret_key,
    mask_secret,
    redact_mapping,
    resolve_mapping,
    resolve_value,
)


def test_is_secret_key_positive():
    for key in (
        "api_key",
        "api_secret_key",
        "bot_token",
        "app_token",
        "password",
        "client_secret",
        "access_token",
        "WHATSAPP_APP_SECRET",
    ):
        assert is_secret_key(key), key


def test_is_secret_key_negative():
    for key in (
        "channel",
        "from_number",
        "mention_only",
        "chat_id",
        "total_tokens",
        "max_tokens",
        "model",
    ):
        assert not is_secret_key(key), key


def test_mask_secret_variants():
    assert mask_secret("sk-abcd1234") == "sk••••••34"
    assert mask_secret("abc") == "•••"  # short -> fully masked
    assert mask_secret("") == ""
    assert mask_secret(None) == ""
    assert mask_secret(env_ref("SOME_VAR")) == "••••• (from env)"


def test_redact_mapping_masks_only_secret_keys():
    src = {
        "channel": "#research",
        "api_secret_key": "super-secret-value",
        "nested": {"bot_token": "xoxb-123456789", "count": 5},
        "list": [{"password": "hunter2xyz"}],
    }
    out = redact_mapping(src)
    assert out["channel"] == "#research"
    assert out["api_secret_key"] != "super-secret-value"
    assert "••" in out["api_secret_key"]
    assert out["nested"]["bot_token"] != "xoxb-123456789"
    assert out["nested"]["count"] == 5
    assert out["list"][0]["password"] != "hunter2xyz"
    # Original is untouched (no mutation).
    assert src["api_secret_key"] == "super-secret-value"


def test_is_env_ref():
    assert is_env_ref("${MY_VAR}")
    assert not is_env_ref("plain")
    assert not is_env_ref("${bad var}")


def test_externalize_and_resolve_roundtrip(monkeypatch):
    stored: dict[str, str] = {}

    def setter(var, value):
        stored[var] = value
        monkeypatch.setenv(var, value)

    config = {
        "channel": "#ops",
        "api_key_id": "keyid-123",
        "api_secret_key": "topsecret-xyz",
    }
    safe = externalize_secrets(config, var_prefix="OJ_BINDING_ab12", setter=setter)

    # Non-secret field is untouched; secret fields become ${VAR} references.
    assert safe["channel"] == "#ops"
    assert is_env_ref(safe["api_key_id"])
    assert is_env_ref(safe["api_secret_key"])
    # The raw secret is nowhere in the stored (DB-bound) config.
    assert "topsecret-xyz" not in str(safe)
    assert "keyid-123" not in str(safe)
    # But it was handed to the setter for out-of-band persistence.
    assert "topsecret-xyz" in stored.values()

    # Resolution reconstitutes the real values from the environment.
    resolved = resolve_mapping(safe)
    assert resolved["api_secret_key"] == "topsecret-xyz"
    assert resolved["api_key_id"] == "keyid-123"
    assert resolved["channel"] == "#ops"


def test_externalize_is_idempotent(monkeypatch):
    def setter(var, value):
        monkeypatch.setenv(var, value)

    config = {"api_key": "abc123"}
    once = externalize_secrets(config, var_prefix="P", setter=setter)
    twice = externalize_secrets(once, var_prefix="P", setter=setter)
    # An already-externalized ${VAR} is left alone.
    assert once == twice


def test_externalize_skips_empty_secret():
    calls = []
    config = {"api_key": ""}
    out = externalize_secrets(
        config, var_prefix="P", setter=lambda v, val: calls.append(v)
    )
    assert out["api_key"] == ""  # empty stays empty
    assert calls == []  # setter never invoked


def test_resolve_value_missing_env_is_empty(monkeypatch):
    monkeypatch.delenv("DEFINITELY_MISSING_VAR", raising=False)
    assert resolve_value("${DEFINITELY_MISSING_VAR}") == ""
    assert resolve_value("literal") == "literal"
