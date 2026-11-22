"""Unit tests for the Stage 3A context compactor."""

from __future__ import annotations

import json
from typing import Sequence

import pytest

from openjarvis.context.compactor import (
    CompactedState,
    CompactionConfig,
    ContextCompactor,
    HeuristicSummarizer,
    LlmSummarizer,
    Summarizer,
    default_token_counter,
)
from openjarvis.core.types import Conversation, Message, Role, ToolCall

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _msg(role: Role, content: str = "", **kw) -> Message:
    return Message(role=role, content=content, **kw)


def _conversation(*messages: Message, max_messages=None) -> Conversation:
    return Conversation(messages=list(messages), max_messages=max_messages)


class _FakeEngine:
    """Stand-in InferenceEngine that returns a canned generate() response."""

    def __init__(self, content: str) -> None:
        self._content = content
        self.calls: list = []

    def generate(self, messages, **kwargs):
        self.calls.append((list(messages), kwargs))
        return {"content": self._content, "usage": {}}


class _BoomEngine:
    def generate(self, messages, **kwargs):
        raise RuntimeError("engine unavailable")


# ---------------------------------------------------------------------------
# Token counting
# ---------------------------------------------------------------------------


def test_default_token_counter_empty_is_zero():
    assert default_token_counter("") == 0


def test_default_token_counter_is_monotonic():
    short = default_token_counter("hi")
    long = default_token_counter("hi " * 100)
    assert long > short >= 1


def test_count_tokens_includes_tool_calls_and_overhead():
    # Arrange
    compactor = ContextCompactor(CompactionConfig(context_window_tokens=1000))
    plain = _msg(Role.USER, "hello world")
    with_tool = _msg(
        Role.ASSISTANT,
        "",
        tool_calls=[ToolCall(id="1", name="calculator", arguments='{"x": 2}')],
    )

    # Act / Assert — a message carrying a tool call costs more than an empty one
    assert compactor.count_tokens([with_tool]) > compactor.count_tokens(
        [_msg(Role.ASSISTANT, "")]
    )
    assert compactor.count_tokens([plain]) >= 1


# ---------------------------------------------------------------------------
# Config validation
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "kwargs",
    [
        {"context_window_tokens": 0},
        {"context_window_tokens": 100, "trigger_ratio": 0.0},
        {"context_window_tokens": 100, "trigger_ratio": 1.5},
        {"context_window_tokens": 100, "keep_recent_messages": -1},
    ],
)
def test_config_rejects_invalid_values(kwargs):
    with pytest.raises(ValueError):
        CompactionConfig(**kwargs)


# ---------------------------------------------------------------------------
# Threshold / trigger behaviour
# ---------------------------------------------------------------------------


def test_should_compact_false_below_threshold():
    # window 1000 tokens, trigger 0.75 -> threshold 750 tokens
    compactor = ContextCompactor(CompactionConfig(context_window_tokens=1000))
    convo = _conversation(_msg(Role.USER, "x" * 40))  # ~10 tokens
    assert compactor.should_compact(convo) is False
    assert compactor.utilization(convo) < 0.75


def test_should_compact_true_at_threshold():
    compactor = ContextCompactor(CompactionConfig(context_window_tokens=1000))
    # ~800 tokens of content -> above the 750-token threshold
    convo = _conversation(_msg(Role.USER, "x" * 3200))
    assert compactor.should_compact(convo) is True


def test_threshold_tokens_uses_ratio():
    compactor = ContextCompactor(
        CompactionConfig(context_window_tokens=1000, trigger_ratio=0.5)
    )
    assert compactor.threshold_tokens() == 500


# ---------------------------------------------------------------------------
# Compaction: preservation, structure, immutability
# ---------------------------------------------------------------------------


def _big_conversation() -> Conversation:
    """A conversation guaranteed to exceed the trigger threshold."""
    msgs = [_msg(Role.SYSTEM, "You are JARVIS.")]
    for i in range(12):
        msgs.append(_msg(Role.USER, f"user request {i} " + "x" * 120))
        msgs.append(_msg(Role.ASSISTANT, f"assistant reply {i} " + "y" * 120))
    return _conversation(*msgs)


def test_compact_preserves_system_messages():
    compactor = ContextCompactor(
        CompactionConfig(context_window_tokens=400, keep_recent_messages=4)
    )
    result = compactor.compact(_big_conversation())
    systems = [m for m in result.messages if m.role is Role.SYSTEM]
    # Original system prompt is still present (plus the injected summary system msg)
    assert any(m.content == "You are JARVIS." for m in systems)


def test_compact_preserves_recent_messages_verbatim():
    convo = _big_conversation()
    compactor = ContextCompactor(
        CompactionConfig(context_window_tokens=400, keep_recent_messages=4)
    )
    result = compactor.compact(convo)
    original_tail = [m for m in convo.messages if m.role is not Role.SYSTEM][-4:]
    result_tail = result.messages[-4:]
    assert [m.content for m in result_tail] == [m.content for m in original_tail]


def test_compact_inserts_single_structured_summary():
    compactor = ContextCompactor(
        CompactionConfig(context_window_tokens=400, keep_recent_messages=4)
    )
    result = compactor.compact(_big_conversation())
    summaries = [m for m in result.messages if m.metadata.get("compacted")]
    assert len(summaries) == 1
    state = summaries[0].metadata["compacted_state"]
    assert set(state.keys()) == {"Goal", "Progress", "Environment", "Pending_Steps"}
    assert summaries[0].metadata["replaced_message_count"] > 0


def test_compact_reduces_token_count():
    convo = _big_conversation()
    compactor = ContextCompactor(
        CompactionConfig(context_window_tokens=400, keep_recent_messages=4)
    )
    before = compactor.count_tokens(convo.messages)
    after = compactor.count_tokens(compactor.compact(convo).messages)
    assert after < before


def test_compact_does_not_mutate_input():
    convo = _big_conversation()
    original_len = len(convo.messages)
    original_first = convo.messages[0].content
    compactor = ContextCompactor(CompactionConfig(context_window_tokens=400))
    compactor.compact(convo)
    assert len(convo.messages) == original_len
    assert convo.messages[0].content == original_first
    assert not any(m.metadata.get("compacted") for m in convo.messages)


def test_compact_noop_returns_fresh_copy_below_threshold():
    convo = _conversation(_msg(Role.USER, "short"))
    compactor = ContextCompactor(CompactionConfig(context_window_tokens=10000))
    result = compactor.compact(convo)
    assert [m.content for m in result.messages] == [m.content for m in convo.messages]
    assert result is not convo
    assert result.messages[0] is not convo.messages[0]  # fresh copy


def test_compact_noop_when_no_older_messages():
    # Over threshold, but everything fits within system + keep_recent window.
    convo = _conversation(
        _msg(Role.SYSTEM, "sys"),
        _msg(Role.USER, "x" * 4000),
        _msg(Role.ASSISTANT, "y" * 4000),
    )
    compactor = ContextCompactor(
        CompactionConfig(context_window_tokens=1000, keep_recent_messages=6)
    )
    assert compactor.should_compact(convo) is True
    result = compactor.compact(convo)
    assert not any(m.metadata.get("compacted") for m in result.messages)


def test_preserve_system_false_summarises_system_too():
    convo = _big_conversation()
    compactor = ContextCompactor(
        CompactionConfig(
            context_window_tokens=400,
            keep_recent_messages=2,
            preserve_system=False,
        )
    )
    result = compactor.compact(convo)
    assert not any(m.content == "You are JARVIS." for m in result.messages)


def test_max_messages_preserved_on_copy():
    convo = _conversation(_msg(Role.USER, "hi"), max_messages=50)
    compactor = ContextCompactor(CompactionConfig(context_window_tokens=10000))
    assert compactor.compact(convo).max_messages == 50


# ---------------------------------------------------------------------------
# CompactedState
# ---------------------------------------------------------------------------


def test_compacted_state_to_dict_uses_spec_keys():
    state = CompactedState(
        goal="g", progress="p", environment="e", pending_steps=["s1", "s2"]
    )
    assert state.to_dict() == {
        "Goal": "g",
        "Progress": "p",
        "Environment": "e",
        "Pending_Steps": ["s1", "s2"],
    }


def test_compacted_state_render_lists_pending():
    state = CompactedState(goal="g", pending_steps=["do X"])
    rendered = state.render()
    assert "Goal: g" in rendered
    assert "- do X" in rendered


# ---------------------------------------------------------------------------
# HeuristicSummarizer
# ---------------------------------------------------------------------------


def test_heuristic_extracts_goal_environment_pending():
    messages = [
        _msg(Role.USER, "Build a web scraper\nsecond line"),
        _msg(
            Role.ASSISTANT,
            "on it",
            tool_calls=[ToolCall(id="1", name="http_request", arguments="{}")],
        ),
        _msg(Role.TOOL, "200 OK", name="http_request"),
        _msg(Role.ASSISTANT, "TODO: add pagination"),
    ]
    state = HeuristicSummarizer().summarize(messages)
    assert state.goal == "Build a web scraper"
    assert "http_request" in state.environment
    assert "TODO: add pagination" in state.pending_steps


def test_heuristic_reports_no_tools_when_none_used():
    state = HeuristicSummarizer().summarize([_msg(Role.USER, "hello")])
    assert "No tools used" in state.environment


def test_heuristic_satisfies_summarizer_protocol():
    assert isinstance(HeuristicSummarizer(), Summarizer)


# ---------------------------------------------------------------------------
# Injected + LLM summarisers
# ---------------------------------------------------------------------------


class _StaticSummarizer:
    def summarize(self, messages: Sequence[Message]) -> CompactedState:
        return CompactedState(
            goal="INJECTED", progress="", environment="", pending_steps=[]
        )


def test_custom_summarizer_is_used():
    compactor = ContextCompactor(
        CompactionConfig(context_window_tokens=400, keep_recent_messages=4),
        summarizer=_StaticSummarizer(),
    )
    result = compactor.compact(_big_conversation())
    summary = next(m for m in result.messages if m.metadata.get("compacted"))
    assert summary.metadata["compacted_state"]["Goal"] == "INJECTED"


def test_llm_summarizer_parses_json_response():
    payload = json.dumps(
        {
            "Goal": "ship feature",
            "Progress": "wrote code",
            "Environment": "python",
            "Pending_Steps": ["write tests"],
        }
    )
    engine = _FakeEngine(payload)
    summariser = LlmSummarizer(engine, model="gemma")
    state = summariser.summarize([_msg(Role.USER, "ship feature")])
    assert state.goal == "ship feature"
    assert state.pending_steps == ["write tests"]
    # Engine was actually invoked with JSON-mode response_format.
    assert engine.calls[0][1]["response_format"] == {"type": "json_object"}


def test_llm_summarizer_falls_back_on_bad_json():
    summariser = LlmSummarizer(_FakeEngine("not json at all"), model="gemma")
    state = summariser.summarize([_msg(Role.USER, "do the thing")])
    # Fallback = heuristic, which recovers the goal from the user message.
    assert state.goal == "do the thing"


def test_llm_summarizer_falls_back_on_engine_error():
    summariser = LlmSummarizer(_BoomEngine(), model="gemma")
    state = summariser.summarize([_msg(Role.USER, "recover gracefully")])
    assert state.goal == "recover gracefully"


def test_llm_summarizer_coerces_nonlist_pending_steps():
    payload = json.dumps(
        {"Goal": "g", "Progress": "p", "Environment": "e", "Pending_Steps": "single"}
    )
    state = LlmSummarizer(_FakeEngine(payload), model="gemma").summarize(
        [_msg(Role.USER, "g")]
    )
    assert state.pending_steps == ["single"]


def test_llm_summarizer_falls_back_on_non_dict_json():
    # A JSON array is valid JSON but not the expected object shape -> fallback.
    summariser = LlmSummarizer(_FakeEngine("[1, 2, 3]"), model="gemma")
    state = summariser.summarize([_msg(Role.USER, "array goal")])
    assert state.goal == "array goal"


def test_llm_summarizer_falls_back_on_empty_content():
    summariser = LlmSummarizer(_FakeEngine("   "), model="gemma")
    state = summariser.summarize([_msg(Role.USER, "empty goal")])
    assert state.goal == "empty goal"


# ---------------------------------------------------------------------------
# Additional branch coverage
# ---------------------------------------------------------------------------


def test_count_tokens_includes_message_name():
    compactor = ContextCompactor(CompactionConfig(context_window_tokens=1000))
    named = _msg(Role.TOOL, "result", name="calculator")
    unnamed = _msg(Role.TOOL, "result")
    assert compactor.count_tokens([named]) > compactor.count_tokens([unnamed])


def test_heuristic_goal_fallback_when_no_user_message():
    state = HeuristicSummarizer().summarize([_msg(Role.ASSISTANT, "thinking")])
    assert "no explicit user goal" in state.goal


def test_heuristic_tool_name_from_standalone_tool_message():
    # TOOL message name not previously seen via a tool_call.
    state = HeuristicSummarizer().summarize(
        [_msg(Role.USER, "go"), _msg(Role.TOOL, "ok", name="db_query")]
    )
    assert "db_query" in state.environment


def test_config_property_exposes_config():
    config = CompactionConfig(context_window_tokens=2048)
    assert ContextCompactor(config).config is config


def test_keep_recent_zero_summarises_all_body_messages():
    convo = _big_conversation()
    compactor = ContextCompactor(
        CompactionConfig(context_window_tokens=400, keep_recent_messages=0)
    )
    result = compactor.compact(convo)
    # No non-system body message survives; only system + one summary remain.
    non_system = [m for m in result.messages if m.role is not Role.SYSTEM]
    assert non_system == []
    assert any(m.metadata.get("compacted") for m in result.messages)
