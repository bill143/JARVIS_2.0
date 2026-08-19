"""/chat -> tool call -> response, plus /tools/execute and error envelopes."""


def test_chat_triggers_tool_call_and_returns_result(client):
    resp = client.post("/chat", json={"message": "compute 2+2", "session_id": "it-chat"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is True
    data = body["data"]
    assert "4" in data["reply"]
    assert data["provider"] == "mock"
    assert data["iterations"] >= 2
    tools_used = [e["tool"] for e in data["tool_events"]]
    assert "python_exec" in tools_used
    assert all(e["status"] == "ok" for e in data["tool_events"])


def test_plain_chat_without_tools(client):
    resp = client.post("/chat", json={"message": "hello there"})
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["tool_events"] == []
    assert "hello there" in data["reply"]


def test_chat_validation_error_envelope(client):
    resp = client.post("/chat", json={"message": ""})
    assert resp.status_code == 400
    body = resp.json()
    assert body["success"] is False
    assert body["error"]["code"] == "VALIDATION_ERROR"
    assert body["error"]["requestId"]


def test_tools_execute_endpoint(client):
    resp = client.post("/tools/execute", json={"tool": "web_search", "arguments": {"query": "jarvis"}})
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["status"] == "ok"
    assert len(data["output"]["results"]) == 5


def test_tools_execute_rejects_unknown_tool(client):
    resp = client.post("/tools/execute", json={"tool": "shell_exec", "arguments": {}})
    assert resp.status_code == 403
    body = resp.json()
    assert body["success"] is False
    assert body["error"]["code"] == "TOOL_NOT_ALLOWED"


def test_tools_execute_rejects_bad_arguments(client):
    resp = client.post("/tools/execute", json={"tool": "python_exec", "arguments": {"kode": "print(1)"}})
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "TOOL_VALIDATION_ERROR"


def test_health_reports_configuration(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["status"] == "ok"
    assert data["providers"] == {"openai": False, "anthropic": False, "deepgram": False, "elevenlabs": False}
    assert "python_exec" in data["tools"]
