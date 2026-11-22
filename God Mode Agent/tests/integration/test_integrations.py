"""Integration tests for the integrations catalog (/integrations/* ext)."""

from __future__ import annotations

import pytest


@pytest.fixture()
def int_client(app, admin_tokens):
    from fastapi.testclient import TestClient

    from jarvis_api.integrations_routes import register_integrations

    register_integrations(app)
    with TestClient(app) as c:
        c.headers.update({"Authorization": f"Bearer {admin_tokens['access_token']}"})
        yield c


def test_catalog_has_providers_tools_planned(int_client):
    r = int_client.get("/integrations/catalog")
    assert r.status_code == 200, r.text
    data = r.json()["data"]["integrations"]
    cats = {i["category"] for i in data}
    assert {"AI Provider", "Tool", "Planned"}.issubset(cats)
    names = {i["name"] for i in data}
    assert "OpenAI" in names and "Slack" in names


def test_catalog_provider_status_reflects_offline_keys(int_client):
    data = int_client.get("/integrations/catalog").json()["data"]["integrations"]
    openai = next(i for i in data if i["name"] == "OpenAI")
    # conftest settings supply no keys, so the provider is available (not connected)
    assert openai["status"] == "available"
    planned = [i for i in data if i["category"] == "Planned"]
    assert all(i["status"] == "planned" for i in planned)
