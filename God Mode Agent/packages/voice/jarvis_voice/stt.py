"""Speech-to-text: Deepgram primary, local whisper fallback, deterministic mock.

Mock convention (used by tests and offline development): audio bytes that start
with b"text:" are transcribed as the remaining UTF-8 string.
"""

from __future__ import annotations

import httpx

from jarvis_shared.config import Settings
from jarvis_shared.logging import get_logger, log_event

logger = get_logger("jarvis.voice.stt")

DEEPGRAM_URL = "https://api.deepgram.com/v1/listen?model=nova-2&smart_format=true"


async def _deepgram(audio: bytes, api_key: str) -> dict | None:
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.post(
                DEEPGRAM_URL,
                content=audio,
                headers={"Authorization": f"Token {api_key}", "Content-Type": "audio/wav"},
            )
        if resp.status_code == 200:
            alt = resp.json()["results"]["channels"][0]["alternatives"][0]
            return {"text": alt.get("transcript", ""), "engine": "deepgram"}
    except (httpx.HTTPError, KeyError, IndexError, ValueError) as exc:
        log_event(logger, "stt.deepgram_failed", error=str(exc))
    return None


def _local_whisper(audio: bytes) -> dict | None:
    try:
        import tempfile
        from pathlib import Path

        from faster_whisper import WhisperModel  # optional heavy dependency

        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            f.write(audio)
            tmp = Path(f.name)
        try:
            model = WhisperModel("tiny", device="cpu", compute_type="int8")
            segments, _info = model.transcribe(str(tmp))
            text = " ".join(s.text.strip() for s in segments)
            return {"text": text.strip(), "engine": "whisper-local"}
        finally:
            tmp.unlink(missing_ok=True)
    except ImportError:
        return None
    except Exception as exc:
        log_event(logger, "stt.whisper_failed", error=str(exc))
        return None


def _mock(audio: bytes) -> dict:
    if audio.startswith(b"text:"):
        return {"text": audio[5:].decode("utf-8", errors="replace").strip(), "engine": "mock"}
    return {"text": "", "engine": "mock", "note": "unrecognized audio (mock STT only decodes b'text:...' payloads)"}


async def transcribe_audio(audio: bytes, settings: Settings) -> dict:
    if not audio:
        return {"text": "", "engine": "mock", "note": "empty audio"}
    if settings.deepgram_api_key:
        result = await _deepgram(audio, settings.deepgram_api_key)
        if result is not None:
            return result
    if settings.enable_fallbacks:
        result = _local_whisper(audio)
        if result is not None:
            return result
        return _mock(audio)
    return {"text": "", "engine": "none", "note": "no STT provider configured and fallbacks disabled"}
