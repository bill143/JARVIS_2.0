"""Secrets never leave the Agent Manager API in plaintext (Stage 2)."""

from __future__ import annotations

import pytest

pytest.importorskip("fastapi", reason="openjarvis[server] not installed")

from openjarvis.server.agent_manager_routes import (  # noqa: E402
    _redact_agent,
    _redact_binding,
    _redact_bindings,
)


def test_redact_agent_masks_config_secrets():
    agent = {
        "id": "a1",
        "name": "n",
        "config": {"model": "gpt-4o", "api_key": "sk-plaintext-123456"},
    }
    out = _redact_agent(agent)
    assert out["config"]["model"] == "gpt-4o"
    assert out["config"]["api_key"] != "sk-plaintext-123456"
    assert "sk-plaintext-123456" not in str(out)
    # Original untouched.
    assert agent["config"]["api_key"] == "sk-plaintext-123456"


def test_redact_agent_handles_none_and_missing_config():
    assert _redact_agent(None) is None
    assert _redact_agent({"id": "x"}) == {"id": "x"}


def test_redact_binding_masks_channel_secrets():
    binding = {
        "id": "b1",
        "channel_type": "sendblue",
        "config": {
            "from_number": "+15551234567",
            "api_secret_key": "supersecret-value-xyz",
        },
    }
    out = _redact_binding(binding)
    assert out["config"]["from_number"] == "+15551234567"
    assert "supersecret-value-xyz" not in str(out)


def test_redact_bindings_list():
    bindings = [
        {"id": "b1", "config": {"bot_token": "xoxb-abcdef123456"}},
        {"id": "b2", "config": {"channel": "#ops"}},
    ]
    out = _redact_bindings(bindings)
    assert "xoxb-abcdef123456" not in str(out)
    assert out[1]["config"]["channel"] == "#ops"
