"""Unit tests for the Stage 3B dynamic tool RAG indexer."""

from __future__ import annotations

import pytest

from openjarvis.tools._stubs import ToolSpec
from openjarvis.tools.tool_indexer import (
    ScoredTool,
    ToolIndexer,
    build_index,
    function_schema,
)


def _spec(name: str, description: str, *, category: str = "", params=None) -> ToolSpec:
    return ToolSpec(
        name=name,
        description=description,
        category=category,
        parameters=params or {"type": "object", "properties": {}},
    )


def _sample_tools():
    return [
        _spec("web_search", "Search the web for pages and news", category="search"),
        _spec("calculator", "Evaluate arithmetic and math expressions"),
        _spec(
            "http_request",
            "Make an HTTP request to a URL",
            params={"type": "object", "properties": {"url": {}, "method": {}}},
        ),
        _spec("pdf_reader", "Extract text from a PDF document"),
        _spec("db_query", "Run a SQL query against the database", category="data"),
        _spec("file_write", "Write content to a file on disk"),
    ]


# ---------------------------------------------------------------------------
# Indexing
# ---------------------------------------------------------------------------


def test_index_counts_tools():
    indexer = build_index(_sample_tools())
    assert indexer.tool_count == 6


def test_index_dedupes_by_name_last_wins():
    indexer = ToolIndexer()
    indexer.index([_spec("t", "first version")])
    indexer.index([_spec("t", "second version updated")])
    assert indexer.tool_count == 1
    names = indexer.selected_names("second")
    assert names == ["t"]


def test_index_accepts_basetool_like_objects():
    class _FakeTool:
        spec = _spec("faker", "does fake things")

    indexer = build_index([_FakeTool()])
    assert indexer.tool_count == 1


def test_index_rejects_unindexable_object():
    with pytest.raises(TypeError):
        ToolIndexer().index([object()])


def test_index_empty_iterable_is_noop():
    indexer = ToolIndexer()
    indexer.index([])
    assert indexer.tool_count == 0
    assert indexer.retrieve("anything") == []


# ---------------------------------------------------------------------------
# Retrieval relevance
# ---------------------------------------------------------------------------


def test_retrieve_ranks_relevant_tool_first():
    indexer = build_index(_sample_tools())
    top = indexer.retrieve("please search the web for recent news")
    assert top[0].spec.name == "web_search"


def test_retrieve_matches_on_parameter_names():
    indexer = build_index(_sample_tools())
    names = indexer.selected_names("send a request to this url")
    assert "http_request" in names


def test_retrieve_respects_max_tools_budget():
    indexer = build_index(_sample_tools(), max_tools=2)
    assert len(indexer.retrieve("search math http pdf sql file")) == 2


def test_retrieve_top_k_overrides_max_tools():
    indexer = build_index(_sample_tools())
    assert len(indexer.retrieve("search math http pdf sql file", top_k=1)) == 1


def test_retrieve_empty_index_returns_empty():
    assert ToolIndexer().retrieve("anything") == []


def test_retrieve_top_k_zero_returns_empty():
    indexer = build_index(_sample_tools())
    assert indexer.retrieve("search", top_k=0) == []


# ---------------------------------------------------------------------------
# Fallback / floor behaviour
# ---------------------------------------------------------------------------


def test_no_match_returns_stable_fallback_up_to_limit():
    indexer = build_index(_sample_tools(), max_tools=4)
    result = indexer.retrieve("zzzz nonexistent gibberish")
    assert len(result) == 4
    assert all(s.score == 0.0 for s in result)
    # Deterministic: alphabetical by name when all scores tie at 0.
    names = [s.spec.name for s in result]
    assert names == sorted(names)


def test_floor_pads_when_few_match():
    indexer = build_index(_sample_tools(), min_tools=3, max_tools=5)
    # Only 'calculator' should match strongly on 'arithmetic'.
    result = indexer.retrieve("arithmetic")
    assert result[0].spec.name == "calculator"
    assert len(result) >= 3  # padded up to min_tools


def test_no_padding_below_min_when_limit_smaller():
    indexer = build_index(_sample_tools(), min_tools=3, max_tools=5)
    result = indexer.retrieve("arithmetic", top_k=1)
    assert len(result) == 1
    assert result[0].spec.name == "calculator"


# ---------------------------------------------------------------------------
# Two-pass injection output
# ---------------------------------------------------------------------------


def test_select_functions_returns_openai_schemas():
    indexer = build_index(_sample_tools())
    fns = indexer.select_functions("search the web", top_k=1)
    assert fns[0]["type"] == "function"
    assert fns[0]["function"]["name"] == "web_search"
    assert "parameters" in fns[0]["function"]


def test_function_schema_defaults_parameters():
    spec = ToolSpec(name="x", description="d", parameters={})
    schema = function_schema(spec)
    assert schema["function"]["parameters"] == {"type": "object", "properties": {}}


# ---------------------------------------------------------------------------
# Config validation + custom scorer
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "kwargs",
    [
        {"max_tools": 0},
        {"min_tools": -1},
    ],
)
def test_config_validation(kwargs):
    with pytest.raises(ValueError):
        ToolIndexer(**kwargs)


def test_min_tools_above_max_is_clamped_not_error():
    # min_tools is a soft floor; a smaller budget simply caps it at runtime.
    indexer = build_index(_sample_tools(), min_tools=5, max_tools=2)
    assert len(indexer.retrieve("search math http pdf sql file")) == 2


def test_custom_score_fn_is_used():
    # Scorer that ranks purely by name length (longest first).
    def by_len(query_tokens, entry):
        return float(len(entry.spec.name))

    indexer = ToolIndexer(score_fn=by_len)
    indexer.index(_sample_tools())
    top = indexer.retrieve("ignored", top_k=1)
    assert top[0].spec.name == "http_request"  # longest name


def test_scored_tool_carries_score():
    indexer = build_index(_sample_tools())
    top = indexer.retrieve("calculator math", top_k=1)
    assert isinstance(top[0], ScoredTool)
    assert top[0].score > 0.0
