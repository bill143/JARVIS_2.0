"""End-to-end chat flow: health -> tool-using chat -> audited session logs."""


def test_full_chat_flow_with_audit_trail(client):
    health = client.get("/health")
    assert health.status_code == 200
    assert health.json()["data"]["status"] == "ok"

    chat = client.post("/chat", json={"message": "search for multimodal agents", "session_id": "e2e-1"})
    assert chat.status_code == 200
    data = chat.json()["data"]
    assert any(e["tool"] == "web_search" for e in data["tool_events"])
    assert "web_search" in data["reply"] or "result" in data["reply"].lower()

    logs = client.get("/sessions/e2e-1/logs")
    assert logs.status_code == 200
    payload = logs.json()["data"]
    audit_tools = [a["tool"] for a in payload["audit"]]
    assert "web_search" in audit_tools
    audit_entry = next(a for a in payload["audit"] if a["tool"] == "web_search")
    assert audit_entry["status"] == "ok"
    assert audit_entry["ts"]
    assert "multimodal" in audit_entry["args_summary"]
    event_kinds = [e["kind"] for e in payload["events"]]
    assert "chat" in event_kinds


def test_multi_turn_context_is_maintained(client):
    client.post("/chat", json={"message": "hello, my name test marker is zebra42", "session_id": "e2e-ctx"})
    second = client.post("/chat", json={"message": "compute 10+5", "session_id": "e2e-ctx"})
    assert second.status_code == 200
    assert "15" in second.json()["data"]["reply"]
