"""Realtime voice loop: transcript streaming, agent reply, TTS, barge-in handling."""

from __future__ import annotations

import asyncio
import base64

from jarvis_shared.config import Settings
from jarvis_shared.logging import get_logger, log_event
from jarvis_voice.stt import transcribe_audio
from jarvis_voice.tts import synthesize_speech

logger = get_logger("jarvis.voice.loop")


class VoiceSession:
    """One websocket voice session.

    Incoming messages:
      {"type": "audio",  "data_b64": "..."}  -> append audio; stream partial transcript
      {"type": "text",   "text": "..."}      -> treat as a final transcript directly
      {"type": "commit"}                     -> finalize: transcript -> agent -> TTS
      {"type": "barge_in"}                   -> interrupt the in-flight reply/TTS

    Outgoing events: transcript.partial, transcript.final, reply.text,
    tts.audio, interrupted, error.
    """

    def __init__(self, agent, settings: Settings, send, session_id: str = "voice"):
        self.agent = agent
        self.settings = settings
        self.send = send  # async callable(dict)
        self.session_id = session_id
        self._buffer = bytearray()
        self._task: asyncio.Task | None = None
        self._interrupted = False

    async def handle(self, msg: dict) -> None:
        msg_type = msg.get("type", "")
        if msg_type == "audio":
            try:
                self._buffer += base64.b64decode(msg.get("data_b64", ""))
            except Exception:
                await self.send({"type": "error", "message": "invalid base64 audio chunk"})
                return
            partial = await transcribe_audio(bytes(self._buffer), self.settings)
            await self.send({"type": "transcript.partial", "text": partial.get("text", ""), "engine": partial.get("engine")})
        elif msg_type == "text":
            await self._respond(str(msg.get("text", "")).strip(), engine="direct")
        elif msg_type == "commit":
            final = await transcribe_audio(bytes(self._buffer), self.settings)
            self._buffer.clear()
            await self._respond(final.get("text", ""), engine=final.get("engine", "mock"))
        elif msg_type == "barge_in":
            await self.barge_in()
        else:
            await self.send({"type": "error", "message": f"unknown message type '{msg_type}'"})

    async def _respond(self, transcript: str, engine: str) -> None:
        await self.send({"type": "transcript.final", "text": transcript, "engine": engine})
        if not transcript:
            await self.send({"type": "error", "message": "empty transcript"})
            return
        self._interrupted = False
        self._task = asyncio.create_task(self._reply_pipeline(transcript))
        try:
            await self._task
        except asyncio.CancelledError:
            log_event(logger, "voice.interrupted", session_id=self.session_id)
        finally:
            self._task = None

    async def _reply_pipeline(self, transcript: str) -> None:
        result = await self.agent.run(transcript, session_id=self.session_id)
        if self._interrupted:
            return
        await self.send({"type": "reply.text", "text": result.reply, "provider": result.provider})
        tts = await synthesize_speech(result.reply, self.settings)
        if self._interrupted:
            return
        await self.send({
            "type": "tts.audio",
            "engine": tts.get("engine"),
            "audio_b64": tts.get("audio_b64", ""),
            "media_type": tts.get("media_type", ""),
            "note": tts.get("note", ""),
        })

    async def barge_in(self) -> None:
        self._interrupted = True
        if self._task and not self._task.done():
            self._task.cancel()
        await self.send({"type": "interrupted"})
