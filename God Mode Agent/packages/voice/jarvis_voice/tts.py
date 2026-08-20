"""Text-to-speech: Kokoro local service first, then ElevenLabs, pyttsx3, sine-wave mock."""

from __future__ import annotations

import base64
import io
import math
import struct
import wave

import httpx

from jarvis_shared.config import Settings
from jarvis_shared.logging import get_logger, log_event
from jarvis_voice.voices import resolve_voice

logger = get_logger("jarvis.voice.tts")

ELEVENLABS_VOICE = "21m00Tcm4TlvDq8ikWAM"  # default "Rachel" voice
ELEVENLABS_URL = "https://api.elevenlabs.io/v1/text-to-speech/{voice}"


async def _kokoro(text: str, agent_id: str | None, settings: Settings) -> dict | None:
    """Local Kokoro TTS service (loopback). Voice chosen via configs/voices.yaml."""
    voice = resolve_voice(agent_id, settings)
    try:
        async with httpx.AsyncClient(timeout=settings.kokoro_timeout_seconds) as client:
            resp = await client.post(
                f"{settings.kokoro_tts_url}/say",
                params={"voice": voice, "speed": "1.0"},
                content=text[:2500].encode("utf-8"),
                headers={"Content-Type": "text/plain; charset=utf-8"},
            )
        if resp.status_code == 200:
            return {
                "engine": "kokoro",
                "voice": voice,
                "audio_b64": base64.b64encode(resp.content).decode(),
                "media_type": "audio/wav",
                "note": "",
            }
        log_event(logger, "tts.kokoro_rejected", status=resp.status_code, voice=voice, detail=resp.text[:200])
    except httpx.HTTPError as exc:
        log_event(logger, "tts.kokoro_unavailable", error=str(exc), voice=voice)
    return None


async def _elevenlabs(text: str, api_key: str) -> dict | None:
    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            resp = await client.post(
                ELEVENLABS_URL.format(voice=ELEVENLABS_VOICE),
                json={"text": text[:2500], "model_id": "eleven_turbo_v2"},
                headers={"xi-api-key": api_key},
            )
        if resp.status_code == 200:
            return {
                "engine": "elevenlabs",
                "audio_b64": base64.b64encode(resp.content).decode(),
                "media_type": "audio/mpeg",
                "note": "",
            }
    except httpx.HTTPError as exc:
        log_event(logger, "tts.elevenlabs_failed", error=str(exc))
    return None


def _pyttsx3(text: str) -> dict | None:
    try:
        import tempfile
        from pathlib import Path

        import pyttsx3  # optional dependency

        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            tmp = Path(f.name)
        try:
            engine = pyttsx3.init()
            engine.save_to_file(text[:2500], str(tmp))
            engine.runAndWait()
            data = tmp.read_bytes()
            if data:
                return {
                    "engine": "pyttsx3",
                    "audio_b64": base64.b64encode(data).decode(),
                    "media_type": "audio/wav",
                    "note": "",
                }
        finally:
            tmp.unlink(missing_ok=True)
    except ImportError:
        return None
    except Exception as exc:
        log_event(logger, "tts.pyttsx3_failed", error=str(exc))
    return None


def _mock_wav(text: str) -> dict:
    """Deterministic 16-bit mono 16 kHz beep whose length scales with the text."""
    sample_rate = 16000
    duration = min(0.2 + len(text) * 0.005, 2.0)
    n_samples = int(sample_rate * duration)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        frames = bytearray()
        for i in range(n_samples):
            value = int(12000 * math.sin(2 * math.pi * 440 * i / sample_rate))
            frames += struct.pack("<h", value)
        wav.writeframes(bytes(frames))
    return {
        "engine": "mock",
        "audio_b64": base64.b64encode(buf.getvalue()).decode(),
        "media_type": "audio/wav",
        "note": "mock TTS tone (no ElevenLabs key, pyttsx3 unavailable)",
    }


async def synthesize_speech(text: str, settings: Settings, agent_id: str | None = None) -> dict:
    """Public entrypoint. Records every synthesis in the activity log on completion."""
    from jarvis_observability.activity import get_activity_log

    result = await _synthesize_inner(text, settings, agent_id)
    if text.strip():
        get_activity_log().record(
            agent_id or "JARVIS", f"tts: {text[:80]}",
            "completed" if result.get("audio_b64") else "failed",
            model=result.get("engine", ""),
            detail=f"voice={result.get('voice', '')} media={result.get('media_type', '')} chars={len(text)}",
        )
    return result


async def _synthesize_inner(text: str, settings: Settings, agent_id: str | None = None) -> dict:
    if not text.strip():
        return {"engine": "none", "audio_b64": "", "media_type": "", "note": "empty text"}
    if settings.kokoro_tts_enabled:
        result = await _kokoro(text, agent_id, settings)
        if result is not None:
            return result
    if settings.elevenlabs_api_key:
        result = await _elevenlabs(text, settings.elevenlabs_api_key)
        if result is not None:
            return result
    if settings.enable_fallbacks:
        result = _pyttsx3(text)
        if result is not None:
            return result
        return _mock_wav(text)
    return {"engine": "none", "audio_b64": "", "media_type": "", "note": "no TTS provider and fallbacks disabled"}
