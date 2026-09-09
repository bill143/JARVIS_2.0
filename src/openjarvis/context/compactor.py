"""Context compactor — summarise older turns to fit the context window.

When a :class:`~openjarvis.core.types.Conversation` grows past a configurable
fraction of the model's usable context window (default 75%), the
:class:`ContextCompactor` replaces the *older* non-system messages with a single
compact summary message carrying a structured
:class:`CompactedState` (``Goal`` / ``Progress`` / ``Environment`` /
``Pending_Steps``).  System instructions and the most recent turns are always
preserved verbatim.

Design notes
------------
* **Immutable** — :meth:`ContextCompactor.compact` never mutates its input; it
  returns a brand-new :class:`Conversation` with fresh :class:`Message` objects.
* **Injectable summariser** — the summarisation strategy is a
  :class:`Summarizer` (a :class:`typing.Protocol`).  The default
  :class:`HeuristicSummarizer` is dependency-free and deterministic so unit
  tests stay hermetic; :class:`LlmSummarizer` wraps any
  :class:`~openjarvis.engine._stubs.InferenceEngine` for production use.
* **Injectable token counter** — token accounting defaults to a cheap
  characters/4 heuristic (:func:`default_token_counter`) but any
  ``Callable[[str], int]`` (e.g. a real tokenizer) can be supplied.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import (
    Any,
    Callable,
    Dict,
    List,
    Optional,
    Protocol,
    Sequence,
    Tuple,
    runtime_checkable,
)

from openjarvis.core.types import Conversation, Message, Role

# Per-message structural overhead (role tag, delimiters) — mirrors the small
# fixed cost real chat tokenizers add per message.
_PER_MESSAGE_TOKEN_OVERHEAD = 4

# Rough bytes-per-token ratio for the heuristic counter.  Four characters per
# token is the widely-used approximation for English BPE tokenizers.
_CHARS_PER_TOKEN = 4


# ---------------------------------------------------------------------------
# Token counting
# ---------------------------------------------------------------------------


def default_token_counter(text: str) -> int:
    """Estimate the token count of *text* with a chars/4 heuristic.

    This is intentionally dependency-free.  Supply a real tokenizer's
    ``encode`` length via :class:`CompactionConfig.token_counter` when exact
    accounting matters.
    """
    if not text:
        return 0
    return max(1, len(text) // _CHARS_PER_TOKEN)


def _message_tokens(message: Message, counter: Callable[[str], int]) -> int:
    """Count tokens for a single message, including tool-call payloads."""
    total = _PER_MESSAGE_TOKEN_OVERHEAD + counter(message.content or "")
    if message.name:
        total += counter(message.name)
    for call in message.tool_calls or ():
        total += counter(call.name) + counter(call.arguments)
    return total


# ---------------------------------------------------------------------------
# Structured compacted state
# ---------------------------------------------------------------------------


@dataclass(slots=True)
class CompactedState:
    """Structured summary of the turns that were compacted away.

    The :meth:`to_dict` keys match the specification exactly
    (``Goal`` / ``Progress`` / ``Environment`` / ``Pending_Steps``).
    """

    goal: str = ""
    progress: str = ""
    environment: str = ""
    pending_steps: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        """Serialise using the canonical capitalised specification keys."""
        return {
            "Goal": self.goal,
            "Progress": self.progress,
            "Environment": self.environment,
            "Pending_Steps": list(self.pending_steps),
        }

    def render(self) -> str:
        """Render a human/model-readable text block for the summary message."""
        lines = [
            "[Compacted context — earlier turns summarised]",
            f"Goal: {self.goal}".rstrip(),
            f"Progress: {self.progress}".rstrip(),
            f"Environment: {self.environment}".rstrip(),
        ]
        if self.pending_steps:
            lines.append("Pending steps:")
            lines.extend(f"- {step}" for step in self.pending_steps)
        else:
            lines.append("Pending steps: (none)")
        return "\n".join(lines)


# ---------------------------------------------------------------------------
# Summariser strategies
# ---------------------------------------------------------------------------


@runtime_checkable
class Summarizer(Protocol):
    """Turns a sequence of older messages into a :class:`CompactedState`."""

    def summarize(self, messages: Sequence[Message]) -> CompactedState: ...


class HeuristicSummarizer:
    """Deterministic, dependency-free summariser used as the default/fallback.

    It does not call any model — it extracts a best-effort structured state
    directly from the messages so the compactor is always usable (and unit
    tests stay hermetic).  For higher-quality summaries use
    :class:`LlmSummarizer`.
    """

    _PENDING_PREFIXES = ("todo:", "next:", "pending:", "- [ ]")

    def summarize(self, messages: Sequence[Message]) -> CompactedState:
        goal = self._first_user_text(messages)
        tool_names = self._tool_names(messages)
        assistant_turns = sum(1 for m in messages if m.role is Role.ASSISTANT)
        tool_turns = sum(1 for m in messages if m.role is Role.TOOL)
        progress = (
            f"{assistant_turns} assistant turn(s) and {tool_turns} tool result(s) "
            f"across {len(messages)} compacted message(s)."
        )
        environment = (
            "Tools used: " + ", ".join(tool_names) if tool_names else "No tools used."
        )
        return CompactedState(
            goal=goal,
            progress=progress,
            environment=environment,
            pending_steps=self._pending_steps(messages),
        )

    @staticmethod
    def _first_user_text(messages: Sequence[Message]) -> str:
        for message in messages:
            if message.role is Role.USER and message.content.strip():
                return message.content.strip().splitlines()[0][:280]
        return "(no explicit user goal found in compacted turns)"

    @staticmethod
    def _tool_names(messages: Sequence[Message]) -> List[str]:
        names: List[str] = []
        for message in messages:
            for call in message.tool_calls or ():
                if call.name not in names:
                    names.append(call.name)
            if message.role is Role.TOOL and message.name and message.name not in names:
                names.append(message.name)
        return names

    def _pending_steps(self, messages: Sequence[Message]) -> List[str]:
        steps: List[str] = []
        for message in messages:
            for line in (message.content or "").splitlines():
                stripped = line.strip()
                lowered = stripped.lower()
                if any(lowered.startswith(p) for p in self._PENDING_PREFIXES):
                    steps.append(stripped)
        return steps


class LlmSummarizer:
    """Summariser backed by an :class:`InferenceEngine` (JSON-mode output).

    Falls back to :class:`HeuristicSummarizer` if the engine errors or returns
    output that cannot be parsed into the expected structure, so compaction is
    never a hard failure.
    """

    _SYSTEM_PROMPT = (
        "You compress an AI agent's conversation history. Read the older turns "
        "and return ONLY a JSON object with keys 'Goal' (string), 'Progress' "
        "(string), 'Environment' (string) and 'Pending_Steps' (array of "
        "strings). Be concise and factual; do not invent details."
    )

    def __init__(
        self,
        engine: Any,
        *,
        model: str,
        max_tokens: int = 512,
        temperature: float = 0.0,
        fallback: Optional[Summarizer] = None,
    ) -> None:
        self._engine = engine
        self._model = model
        self._max_tokens = max_tokens
        self._temperature = temperature
        self._fallback = fallback or HeuristicSummarizer()

    def summarize(self, messages: Sequence[Message]) -> CompactedState:
        transcript = self._render_transcript(messages)
        prompt = [
            Message(role=Role.SYSTEM, content=self._SYSTEM_PROMPT),
            Message(role=Role.USER, content=transcript),
        ]
        try:
            response = self._engine.generate(
                prompt,
                model=self._model,
                temperature=self._temperature,
                max_tokens=self._max_tokens,
                response_format={"type": "json_object"},
            )
            content = response.get("content", "") if isinstance(response, dict) else ""
            state = self._parse(content)
        except Exception:  # noqa: BLE001 — degrade to heuristic, never crash compaction
            state = None
        return state if state is not None else self._fallback.summarize(messages)

    @staticmethod
    def _render_transcript(messages: Sequence[Message]) -> str:
        return "\n".join(
            f"{m.role.value}: {m.content}".strip() for m in messages if m.content
        )

    @staticmethod
    def _parse(content: str) -> Optional[CompactedState]:
        if not content or not content.strip():
            return None
        try:
            data = json.loads(content)
        except (ValueError, TypeError):
            return None
        if not isinstance(data, dict):
            return None
        pending = data.get("Pending_Steps", [])
        if not isinstance(pending, list):
            pending = [str(pending)]
        return CompactedState(
            goal=str(data.get("Goal", "")),
            progress=str(data.get("Progress", "")),
            environment=str(data.get("Environment", "")),
            pending_steps=[str(step) for step in pending],
        )


# ---------------------------------------------------------------------------
# Compactor
# ---------------------------------------------------------------------------


@dataclass(slots=True)
class CompactionConfig:
    """Configuration for :class:`ContextCompactor`.

    Attributes:
        context_window_tokens: Usable context window of the target model.
        trigger_ratio: Utilisation fraction (0..1] at which compaction fires.
        keep_recent_messages: Number of trailing non-system messages preserved
            verbatim (never summarised).
        preserve_system: Keep every ``system`` message verbatim.
        token_counter: ``Callable[[str], int]`` used for token accounting.
    """

    context_window_tokens: int
    trigger_ratio: float = 0.75
    keep_recent_messages: int = 6
    preserve_system: bool = True
    token_counter: Callable[[str], int] = default_token_counter

    def __post_init__(self) -> None:
        if self.context_window_tokens <= 0:
            raise ValueError("context_window_tokens must be positive")
        if not 0.0 < self.trigger_ratio <= 1.0:
            raise ValueError("trigger_ratio must be in (0, 1]")
        if self.keep_recent_messages < 0:
            raise ValueError("keep_recent_messages must be non-negative")


class ContextCompactor:
    """Compacts a :class:`Conversation` when it exceeds the trigger threshold."""

    def __init__(
        self,
        config: CompactionConfig,
        summarizer: Optional[Summarizer] = None,
    ) -> None:
        self._config = config
        self._summarizer = summarizer or HeuristicSummarizer()

    @property
    def config(self) -> CompactionConfig:
        return self._config

    # -- measurement --------------------------------------------------------

    def count_tokens(self, messages: Sequence[Message]) -> int:
        """Total estimated tokens for *messages*."""
        counter = self._config.token_counter
        return sum(_message_tokens(m, counter) for m in messages)

    def utilization(self, conversation: Conversation) -> float:
        """Fraction (0..1+) of the context window currently consumed."""
        return (
            self.count_tokens(conversation.messages)
            / self._config.context_window_tokens
        )

    def threshold_tokens(self) -> int:
        """Absolute token count at which compaction triggers."""
        return int(self._config.context_window_tokens * self._config.trigger_ratio)

    def should_compact(self, conversation: Conversation) -> bool:
        """True when utilisation has reached :attr:`trigger_ratio`."""
        return self.count_tokens(conversation.messages) >= self.threshold_tokens()

    # -- compaction ---------------------------------------------------------

    def compact(self, conversation: Conversation) -> Conversation:
        """Return a compacted copy of *conversation* (input never mutated).

        No-op (returns an equivalent fresh copy) when the trigger threshold is
        not met or there are no older messages eligible for summarisation.
        """
        if not self.should_compact(conversation):
            return self._copy(conversation.messages, conversation)

        system, older, recent = self._partition(conversation.messages)
        if not older:
            return self._copy(conversation.messages, conversation)

        state = self._summarizer.summarize(older)
        summary = self._summary_message(state, replaced=len(older))
        return self._copy([*system, summary, *recent], conversation)

    def _partition(
        self, messages: Sequence[Message]
    ) -> Tuple[List[Message], List[Message], List[Message]]:
        """Split into (preserved system, older-to-summarise, recent-to-keep)."""
        if self._config.preserve_system:
            system = [m for m in messages if m.role is Role.SYSTEM]
            body = [m for m in messages if m.role is not Role.SYSTEM]
        else:
            system = []
            body = list(messages)
        keep = self._config.keep_recent_messages
        if keep <= 0:
            return system, body, []
        recent = body[-keep:]
        older = body[:-keep]
        return system, older, recent

    @staticmethod
    def _summary_message(state: CompactedState, *, replaced: int) -> Message:
        return Message(
            role=Role.SYSTEM,
            content=state.render(),
            metadata={
                "compacted": True,
                "compacted_state": state.to_dict(),
                "replaced_message_count": replaced,
            },
        )

    @staticmethod
    def _copy(messages: Sequence[Message], source: Conversation) -> Conversation:
        """Build a new Conversation with fresh Message copies (immutability)."""
        clones = [
            Message(
                role=m.role,
                content=m.content,
                name=m.name,
                tool_calls=list(m.tool_calls) if m.tool_calls else None,
                tool_call_id=m.tool_call_id,
                metadata=dict(m.metadata),
            )
            for m in messages
        ]
        return Conversation(messages=clones, max_messages=source.max_messages)


__all__ = [
    "CompactedState",
    "CompactionConfig",
    "ContextCompactor",
    "HeuristicSummarizer",
    "LlmSummarizer",
    "Summarizer",
    "default_token_counter",
]
