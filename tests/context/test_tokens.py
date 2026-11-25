"""Tests for the shared token counter in ``openjarvis.context.tokens``."""

from __future__ import annotations

import builtins
import sys

import pytest

from openjarvis.context import tokens
from openjarvis.context.compactor import default_token_counter
from openjarvis.memory import session as session_mod
from openjarvis.tools.storage import context as storage_context


@pytest.fixture(autouse=True)
def _reset_encoder_cache():
    tokens._tiktoken_encoder.cache_clear()
    yield
    tokens._tiktoken_encoder.cache_clear()


@pytest.fixture
def no_tiktoken(monkeypatch):
    """Make ``import tiktoken`` fail, as on an install without the extra."""
    real_import = builtins.__import__

    def fake_import(name, *args, **kwargs):
        if name == "tiktoken":
            raise ImportError("tiktoken not installed")
        return real_import(name, *args, **kwargs)

    monkeypatch.delitem(sys.modules, "tiktoken", raising=False)
    monkeypatch.setattr(builtins, "__import__", fake_import)


def test_empty_text_is_zero():
    assert tokens.count_tokens("") == 0
    assert tokens.heuristic_token_count("") == 0


def test_heuristic_rounds_up():
    assert tokens.heuristic_token_count("a") == 1
    assert tokens.heuristic_token_count("abcd") == 1
    assert tokens.heuristic_token_count("abcde") == 2


def test_fallback_without_tiktoken(no_tiktoken):
    text = "The quick brown fox jumps over the lazy dog."
    assert not tokens.tiktoken_available()
    assert tokens.count_tokens(text) == tokens.heuristic_token_count(text)


def test_fallback_when_encoding_fails_to_load(monkeypatch):
    tiktoken = pytest.importorskip("tiktoken")

    def boom(_name):
        raise OSError("no network to fetch BPE file")

    monkeypatch.setattr(tiktoken, "get_encoding", boom)
    assert not tokens.tiktoken_available()
    assert tokens.count_tokens("hello world") == tokens.heuristic_token_count(
        "hello world"
    )


@pytest.fixture
def real_tiktoken():
    """The real encoder; skipped if not installed or its BPE file can't load."""
    tiktoken = pytest.importorskip("tiktoken")
    if not tokens.tiktoken_available():
        pytest.skip("cl100k_base BPE file unavailable (offline)")
    return tiktoken.get_encoding("cl100k_base")


def test_exact_counts_with_tiktoken(real_tiktoken):
    text = 'Pull {"uei": "ABC123XYZ", "url": "https://sam.gov/opp/42"} now.'
    assert tokens.count_tokens(text) == len(real_tiktoken.encode(text))


def test_special_token_text_is_counted_not_rejected(real_tiktoken):
    assert tokens.count_tokens("before <|endoftext|> after") > 0


def test_whitespace_split_undercounts_dense_text():
    """The bug this module fixes: word counts miss most tokens in dense text."""
    dense = '{"solicitation":"W912DY-26-R-0042","naics":"236220","set_aside":"SDVOSB"}'
    assert len(dense.split()) == 1
    assert tokens.count_tokens(dense) >= 10


def test_all_budgets_share_one_counter():
    text = "Federal design-build proposal due 2026-12-15 at 14:00 CT."
    expected = tokens.count_tokens(text)
    assert default_token_counter(text) == expected
    assert session_mod._count_tokens(text) == expected
    assert storage_context._count_tokens(text) == expected
