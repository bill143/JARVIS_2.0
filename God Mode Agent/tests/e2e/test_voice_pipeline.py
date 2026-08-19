"""E2E: simulated voice transcript -> agent -> TTS pipeline, plus barge-in."""

import base64


def _b64(data: bytes) -> str:
    return base64.b64encode(data).decode()


def test_voice_transcript_reply_tts_pipeline(client, admin_tokens):
    with client.websocket_connect(f"/realtime/voice?token={admin_tokens['access_token']}") as ws:
        ws.send_json({"type": "audio", "data_b64": _b64(b"text:compute 6*7")})
        partial = ws.receive_json()
        assert partial["type"] == "transcript.partial"
        assert partial["text"] == "compute 6*7"

        ws.send_json({"type": "commit"})
        final = ws.receive_json()
        assert final["type"] == "transcript.final"
        assert final["text"] == "compute 6*7"

        reply = ws.receive_json()
        assert reply["type"] == "reply.text"
        assert "42" in reply["text"]

        tts = ws.receive_json()
        assert tts["type"] == "tts.audio"
        assert tts["engine"] in ("elevenlabs", "pyttsx3", "mock")
        assert len(tts["audio_b64"]) > 100  # real audio payload, whatever the engine


def test_voice_text_input_path(client, admin_tokens):
    with client.websocket_connect(f"/realtime/voice?token={admin_tokens['access_token']}") as ws:
        ws.send_json({"type": "text", "text": "hello jarvis"})
        final = ws.receive_json()
        assert final["type"] == "transcript.final"
        reply = ws.receive_json()
        assert reply["type"] == "reply.text"
        assert "hello jarvis" in reply["text"]
        tts = ws.receive_json()
        assert tts["type"] == "tts.audio"


def test_barge_in_interrupts(client, admin_tokens):
    with client.websocket_connect(f"/realtime/voice?token={admin_tokens['access_token']}") as ws:
        ws.send_json({"type": "barge_in"})
        event = ws.receive_json()
        assert event["type"] == "interrupted"


def test_unknown_voice_message_type_errors(client, admin_tokens):
    with client.websocket_connect(f"/realtime/voice?token={admin_tokens['access_token']}") as ws:
        ws.send_json({"type": "warp_drive"})
        event = ws.receive_json()
        assert event["type"] == "error"
