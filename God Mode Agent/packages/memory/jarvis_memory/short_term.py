"""Rolling per-session context buffer with compression summaries."""

from __future__ import annotations

from jarvis_shared.schemas import Message

SUMMARY_PREFIX = "Conversation summary so far: "


class ContextBuffer:
    """Keeps the last `max_messages` messages per session. When the buffer
    overflows, the oldest half is compressed into a single summary message."""

    def __init__(self, max_messages: int = 24, summary_chars: int = 900):
        self.max_messages = max_messages
        self.summary_chars = summary_chars
        self._sessions: dict[str, list[Message]] = {}
        self._summaries: dict[str, str] = {}

    def add(self, session_id: str, message: Message) -> None:
        buf = self._sessions.setdefault(session_id, [])
        buf.append(message)
        if len(buf) > self.max_messages:
            self._compress(session_id)

    def get(self, session_id: str) -> list[Message]:
        out: list[Message] = []
        summary = self._summaries.get(session_id)
        if summary:
            out.append(Message(role="system", content=SUMMARY_PREFIX + summary))
        out.extend(self._sessions.get(session_id, []))
        return out

    def clear(self, session_id: str) -> None:
        self._sessions.pop(session_id, None)
        self._summaries.pop(session_id, None)

    def _compress(self, session_id: str) -> None:
        buf = self._sessions[session_id]
        half = len(buf) // 2
        old, keep = buf[:half], buf[half:]
        lines = [f"{m.role}: {m.content[:120]}" for m in old if m.content]
        merged = (self._summaries.get(session_id, "") + " | " if self._summaries.get(session_id) else "") + "; ".join(lines)
        self._summaries[session_id] = merged[-self.summary_chars:]
        self._sessions[session_id] = keep
