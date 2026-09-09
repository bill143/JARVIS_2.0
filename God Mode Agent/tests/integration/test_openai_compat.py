"""Integration tests for the OpenAI-compatible shim (/v1/*).

These run fully offline: with no API keys the model router falls back to the
deterministic mock provider, so /v1/chat/completions still returns a real reply.
"""

from __future__ import annotations

import pytest


@pytest.fixture()
def v1_client(app, admin_tokens):
    """Admin-authenticated client with the OpenAI shim registered on the app."""
    from fastapi.testclient import TestClient

    from jarvis_api.openai_compat import register_openai_compat

    register_openai_compat(app)
    with TestClient(app) as c:
        c.headers.update({"Authorization": f"Bearer {admin_tokens['access_token']}"})
        yield c


def test_v1_models_lists_god_mode(v1_client):
    resp = v1_client.get("/v1/models")
    assert resp.status_code == 200, resp.text
    data = resp.json()
    assert data["object"] == "list"
    assert "god-mode-agent" in [m["id"] for m in data["data"]]


def test_v1_chat_completion_openai_shape(v1_client):
    resp = v1_client.post(
        "/v1/chat/completions",
        json={
            "model": "god-mode-agent",
            "messages": [
                {"role": "system", "content": "You are JARVIS."},
                {"role": "user", "content": "Say hello."},
            ],
        },
    )
    assert resp.status_code == 200, resp.text
    data = resp.json()
    assert data["object"] == "chat.completion"
    choice = data["choices"][0]
    assert choice["message"]["role"] == "assistant"
    assert isinstance(choice["message"]["content"], str) and choice["message"]["content"].strip()
    assert choice["finish_reason"] == "stop"
    assert data["usage"]["total_tokens"] >= 1


def test_v1_chat_completion_streaming(v1_client):
    resp = v1_client.post(
        "/v1/chat/completions",
        json={"messages": [{"role": "user", "content": "hello"}], "stream": True},
    )
    assert resp.status_code == 200, resp.text
    assert "text/event-stream" in resp.headers.get("content-type", "")
    body = resp.text
    assert "chat.completion.chunk" in body
    assert "data: [DONE]" in body


def test_v1_chat_requires_messages(v1_client):
    resp = v1_client.post("/v1/chat/completions", json={"model": "god-mode-agent"})
    assert resp.status_code == 400, resp.text
    assert resp.json()["error"]["type"] == "invalid_request_error"
