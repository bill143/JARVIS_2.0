"""/memory upsert + search via the API, including persistence across app restarts."""

from fastapi.testclient import TestClient

from jarvis_api.main import create_app


def test_upsert_then_search(client):
    up = client.post("/memory/upsert", json={
        "text": "the deployment password rotation happens every friday",
        "user_id": "bill", "session_id": "s1", "metadata": {"topic": "ops"},
    })
    assert up.status_code == 200
    record_id = up.json()["data"]["id"]

    search = client.get("/memory/search", params={"q": "password rotation", "user_id": "bill", "session_id": "s1"})
    assert search.status_code == 200
    hits = search.json()["data"]["hits"]
    assert hits and hits[0]["id"] == record_id


def test_namespaces_are_isolated_via_api(client):
    client.post("/memory/upsert", json={"text": "secret alpha notes", "user_id": "alice"})
    search = client.get("/memory/search", params={"q": "secret alpha notes", "user_id": "mallory"})
    hits = search.json()["data"]["hits"]
    assert all("alpha" not in h["text"] for h in hits)


def _auth(c):
    tokens = c.post("/auth/login", json={"username": "admin", "password": "admin123"}).json()["data"]
    c.headers.update({"Authorization": f"Bearer {tokens['access_token']}"})


def test_memory_persists_across_app_restart(settings):
    with TestClient(create_app(settings)) as first:
        _auth(first)
        first.post("/memory/upsert", json={
            "text": "jarvis remembers the garage code", "user_id": "bill", "record_id": "garage",
        })
    # New app instance over the same persist dir = restart.
    with TestClient(create_app(settings)) as second:
        _auth(second)
        search = second.get("/memory/search", params={"q": "garage code", "user_id": "bill"})
        hits = search.json()["data"]["hits"]
        assert any(h["id"] == "garage" for h in hits)


def test_search_requires_query(client):
    resp = client.get("/memory/search")
    assert resp.status_code == 400
    assert resp.json()["error"]["code"] == "VALIDATION_ERROR"
