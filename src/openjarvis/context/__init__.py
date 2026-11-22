"""Context-window management primitives.

The :mod:`openjarvis.context` package holds utilities that keep an agent's
working context within a model's usable window.  The first citizen is the
:class:`~openjarvis.context.compactor.ContextCompactor`, which summarises older
conversation turns into a compact structured state once utilisation crosses a
configurable threshold, while preserving system instructions and the most
recent turns verbatim.
"""

from __future__ import annotations

from openjarvis.context.compactor import (
    CompactedState,
    CompactionConfig,
    ContextCompactor,
    HeuristicSummarizer,
    LlmSummarizer,
    Summarizer,
    default_token_counter,
)

__all__ = [
    "CompactedState",
    "CompactionConfig",
    "ContextCompactor",
    "HeuristicSummarizer",
    "LlmSummarizer",
    "Summarizer",
    "default_token_counter",
]
