"""Context injection — retrieve relevant memory and inject into prompts."""

from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional

from openjarvis.core.events import EventType, get_event_bus
from openjarvis.core.types import Message, Role
from openjarvis.memory.episodic import EpisodicFact, EpisodicMemory
from openjarvis.memory.session import SessionMemory, SessionMemoryItem
from openjarvis.tools.storage._stubs import MemoryBackend, RetrievalResult


@dataclass(slots=True)
class ContextConfig:
    """Controls how retrieved context is injected into prompts."""

    enabled: bool = True
    top_k: int = 5
    min_score: float = 0.1
    max_context_tokens: int = 2048


def _count_tokens(text: str) -> int:
    """Approximate token count via whitespace split."""
    return len(text.split())


def format_context(results: List[RetrievalResult]) -> str:
    """Format retrieval results into a context block.

    Each result is prefixed with its source attribution.
    """
    if not results:
        return ""

    lines = []
    for r in results:
        source_tag = f"[Source: {r.source}]" if r.source else ""
        if source_tag:
            lines.append(f"{source_tag} {r.content}")
        else:
            lines.append(r.content)

    return "\n\n".join(lines)


def build_context_message(
    results: List[RetrievalResult],
) -> Message:
    """Create a system message with formatted context."""
    context_text = format_context(results)
    content = (
        "The following context was retrieved from the knowledge"
        " base. Use it to inform your response, citing sources"
        " where applicable:\n\n" + context_text
    )
    return Message(role=Role.SYSTEM, content=content)


def format_session_turns(items: List[SessionMemoryItem]) -> str:
    """Format session turns into a plain-text transcript block."""
    if not items:
        return ""
    return "\n\n".join(
        f"User: {item.query}\nAssistant: {item.response}" for item in items
    )


def build_session_message(items: List[SessionMemoryItem]) -> Message:
    """Create a system message summarizing recent session turns."""
    content = (
        "The following is recent conversation history. Use it to maintain"
        " continuity with the user:\n\n" + format_session_turns(items)
    )
    return Message(role=Role.SYSTEM, content=content)


def format_episodic_facts(facts: List[EpisodicFact]) -> str:
    """Format episodic facts into a context block, one per line."""
    if not facts:
        return ""
    lines = []
    for f in facts:
        source_tag = f"[Source: {f.source}]" if f.source else ""
        prefix = f"{source_tag} " if source_tag else ""
        lines.append(f"{prefix}{f.fact} (confidence: {f.confidence:.2f})")
    return "\n".join(lines)


def build_episodic_message(facts: List[EpisodicFact]) -> Message:
    """Create a system message with recalled long-term facts."""
    content = (
        "The following facts were recalled from long-term memory. Use them"
        " to inform your response, citing sources where applicable:\n\n"
        + format_episodic_facts(facts)
    )
    return Message(role=Role.SYSTEM, content=content)


def inject_context(
    query: str,
    messages: List[Message],
    backend: Optional[MemoryBackend] = None,
    *,
    config: Optional[ContextConfig] = None,
    session_memory: Optional[SessionMemory] = None,
    episodic_memory: Optional[EpisodicMemory] = None,
) -> List[Message]:
    """Retrieve relevant context and prepend it to *messages*.

    Returns a **new** list — the original list is not mutated.
    If nothing is available to inject, returns the original messages
    unchanged.

    Parameters
    ----------
    query:
        The user query to search for.
    messages:
        The existing message list.
    backend:
        The memory backend to search. Optional — pass ``None`` to skip
        knowledge-base retrieval entirely and rely only on
        *session_memory* / *episodic_memory* (e.g. when no backend has
        anything indexed yet).
    config:
        Context injection settings (uses defaults if ``None``).
    session_memory:
        Optional rolling short-term conversation state. When provided, the
        most recent ``config.top_k`` turns are appended as a context block
        if they fit within the remaining token budget.
    episodic_memory:
        Optional long-term fact store. When provided, up to
        ``config.top_k`` facts relevant to *query* are appended as a
        context block if they fit within the remaining token budget.
    """
    cfg = config or ContextConfig()
    if not cfg.enabled:
        return messages

    context_messages: List[Message] = []
    remaining_tokens = cfg.max_context_tokens

    truncated: List[RetrievalResult] = []
    if backend is not None:
        results = backend.retrieve(query, top_k=cfg.top_k)

        # Filter by minimum score
        results = [r for r in results if r.score >= cfg.min_score]

        # Truncate to max_context_tokens
        total_tokens = 0
        for r in results:
            tokens = _count_tokens(r.content)
            if total_tokens + tokens > remaining_tokens:
                break
            truncated.append(r)
            total_tokens += tokens

        if truncated:
            context_messages.append(build_context_message(truncated))
            remaining_tokens -= total_tokens

    # Session memory: include only if the whole block fits the remaining
    # budget — Phase 1 keeps the policy simple (include-whole-or-skip)
    # rather than truncating mid-transcript.
    session_included = False
    if session_memory is not None:
        session_items = session_memory.retrieve(k=cfg.top_k)
        if session_items:
            session_msg = build_session_message(session_items)
            session_tokens = _count_tokens(session_msg.content)
            if session_tokens <= remaining_tokens:
                context_messages.append(session_msg)
                remaining_tokens -= session_tokens
                session_included = True

    # Episodic memory: same include-whole-or-skip policy.
    episodic_included = False
    if episodic_memory is not None:
        facts = episodic_memory.retrieve_facts(query, k=cfg.top_k)
        if facts:
            episodic_msg = build_episodic_message(facts)
            episodic_tokens = _count_tokens(episodic_msg.content)
            if episodic_tokens <= remaining_tokens:
                context_messages.append(episodic_msg)
                remaining_tokens -= episodic_tokens
                episodic_included = True

    if not context_messages:
        return messages

    # Publish event
    bus = get_event_bus()
    bus.publish(
        EventType.MEMORY_RETRIEVE,
        {
            "context_injection": True,
            "query": query,
            "num_results": len(truncated),
            "total_tokens": cfg.max_context_tokens - remaining_tokens,
            "session_memory_included": session_included,
            "episodic_memory_included": episodic_included,
        },
    )

    # Prepend context blocks (knowledge, then session, then episodic)
    return context_messages + list(messages)


__all__ = [
    "ContextConfig",
    "build_context_message",
    "build_episodic_message",
    "build_session_message",
    "format_context",
    "format_episodic_facts",
    "format_session_turns",
    "inject_context",
]
