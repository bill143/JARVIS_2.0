"""Websocket event streaming: /realtime/chat and /realtime/vision."""

import base64

from jarvis_vision.pngutil import synthetic_frame


def _collect_until_done(ws, limit=50):
    events = []
    for _ in range(limit):
        event = ws.receive_json()
        events.append(event)
        if event.get("type") == "done":
            break
    return events


def test_realtime_chat_streams_tool_events_and_tokens(client, admin_tokens):
    token = admin_tokens["access_token"]
    with client.websocket_connect(f"/realtime/chat?token={token}") as ws:
        ws.send_json({"message": "compute 3*3", "session_id": "ws-chat"})
        events = _collect_until_done(ws)
    types = [e["type"] for e in events]
    assert "tool_event" in types
    assert "token" in types
    assert types[-1] == "done"
    done = events[-1]["data"]
    assert "9" in done["reply"]
    assert done["provider"] == "mock"


def test_realtime_chat_rejects_empty_message(client, admin_tokens):
    token = admin_tokens["access_token"]
    with client.websocket_connect(f"/realtime/chat?token={token}") as ws:
        ws.send_json({"message": ""})
        event = ws.receive_json()
    assert event["type"] == "error"


def test_realtime_vision_analyzes_frames(client, admin_tokens):
    token = admin_tokens["access_token"]
    frame_b64 = base64.b64encode(synthetic_frame()).decode()
    with client.websocket_connect(f"/realtime/vision?token={token}") as ws:
        ws.send_json({"type": "frame", "data_b64": frame_b64, "source": "test-cam"})
        event = ws.receive_json()
    assert event["type"] == "vision.analysis"
    data = event["data"]
    assert data["ok"] is True
    assert data["width"] == 96
    assert data["height"] == 72
    assert data["source"] == "test-cam"
