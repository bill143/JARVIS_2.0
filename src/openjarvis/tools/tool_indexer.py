"""Dynamic Tool RAG — retrieve only the tools relevant to an intent.

Injecting every tool schema into a prompt is wasteful and dilutes the model's
attention.  :class:`ToolIndexer` indexes tool schemas/descriptions and performs
two-pass tool injection:

1. **Retrieve** the top *k* (default 3–5) candidate tools by intent.
2. **Inject** only those schemas (as OpenAI function-calling dicts) into the
   prompt.

The default scorer is a dependency-free IDF-weighted lexical matcher, so the
indexer works with no extra dependencies and is fully deterministic (hermetic in
tests).  The scoring strategy is injectable via ``score_fn`` — supply a dense /
embedding-based scorer for production semantic retrieval, or wire a
``tools.storage`` backend on top of :meth:`ToolIndexer.retrieve`.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, FrozenSet, Iterable, List, Optional, Sequence

from openjarvis.tools._stubs import BaseTool, ToolSpec

# Retrieval defaults: keep the injected tool set small (3–5 tools).
_DEFAULT_MIN_TOOLS = 3
_DEFAULT_MAX_TOOLS = 5

_TOKEN_RE = re.compile(r"[a-z0-9_]+")

# Scorer signature: (query_tokens, entry) -> relevance score (higher = better).
ScoreFn = Callable[[List[str], "ToolIndexEntry"], float]


def _tokenize(text: str) -> List[str]:
    """Lowercase alphanumeric/underscore tokens, length >= 2."""
    return [t for t in _TOKEN_RE.findall(text.lower()) if len(t) >= 2]


@dataclass(slots=True)
class ToolIndexEntry:
    """An indexed tool: its spec plus the derived searchable token set."""

    spec: ToolSpec
    text: str
    tokens: FrozenSet[str]


@dataclass(slots=True)
class ScoredTool:
    """A tool spec paired with its retrieval score."""

    spec: ToolSpec
    score: float


def _spec_of(tool: Any) -> ToolSpec:
    """Accept a ToolSpec, a BaseTool, or anything exposing ``.spec``."""
    if isinstance(tool, ToolSpec):
        return tool
    if isinstance(tool, BaseTool) or hasattr(tool, "spec"):
        return tool.spec
    raise TypeError(f"Cannot index object of type {type(tool)!r}")


def _entry_text(spec: ToolSpec) -> str:
    """Build the searchable text for a tool from its schema."""
    props = spec.parameters.get("properties", {}) if spec.parameters else {}
    param_names = " ".join(props.keys())
    return " ".join(
        filter(None, [spec.name, spec.category, spec.description, param_names])
    )


def function_schema(spec: ToolSpec) -> Dict[str, Any]:
    """Render a ToolSpec as an OpenAI function-calling schema dict."""
    return {
        "type": "function",
        "function": {
            "name": spec.name,
            "description": spec.description,
            "parameters": spec.parameters or {"type": "object", "properties": {}},
        },
    }


@dataclass(slots=True)
class ToolIndexer:
    """Indexes tool schemas and retrieves the most relevant ones by intent.

    Attributes:
        min_tools: When at least one tool matches but fewer than this many do,
            pad the result with the next tools so the agent keeps a minimum
            viable toolset.
        max_tools: Upper bound on tools returned (the injection budget).
        score_fn: Optional custom scorer; defaults to IDF-weighted overlap.
    """

    min_tools: int = _DEFAULT_MIN_TOOLS
    max_tools: int = _DEFAULT_MAX_TOOLS
    score_fn: Optional[ScoreFn] = None
    _entries: Dict[str, ToolIndexEntry] = field(
        default_factory=dict, init=False, repr=False
    )
    _idf: Dict[str, float] = field(default_factory=dict, init=False, repr=False)

    def __post_init__(self) -> None:
        if self.min_tools < 0 or self.max_tools < 1:
            raise ValueError("min_tools must be >= 0 and max_tools >= 1")
        # min_tools is a soft floor; a budget below it simply caps it (see
        # retrieve()), so a min_tools > max_tools config is clamped, not an error.

    @property
    def tool_count(self) -> int:
        return len(self._entries)

    def index(self, tools: Iterable[Any]) -> None:
        """Index (or re-index) *tools*.  Later entries override same-named ones."""
        for tool in tools:
            spec = _spec_of(tool)
            text = _entry_text(spec)
            self._entries[spec.name] = ToolIndexEntry(
                spec=spec, text=text, tokens=frozenset(_tokenize(text))
            )
        self._recompute_idf()

    def _recompute_idf(self) -> None:
        n = len(self._entries)
        self._idf = {}
        if n == 0:
            return
        doc_freq: Dict[str, int] = {}
        for entry in self._entries.values():
            for token in entry.tokens:
                doc_freq[token] = doc_freq.get(token, 0) + 1
        # Smoothed IDF so a term common to every tool still contributes a little.
        for token, freq in doc_freq.items():
            self._idf[token] = math.log((n + 1) / (freq + 0.5)) + 1.0

    def _default_score(self, query_tokens: List[str], entry: ToolIndexEntry) -> float:
        return sum(
            self._idf.get(tok, 0.0) for tok in query_tokens if tok in entry.tokens
        )

    def retrieve(self, intent: str, top_k: Optional[int] = None) -> List[ScoredTool]:
        """Return tools ranked by relevance to *intent* (highest first).

        Returns at most ``top_k or max_tools`` tools.  When at least one tool
        matches but fewer than :attr:`min_tools`, the result is padded with the
        next tools (stable order).  When nothing matches, the first
        ``top_k or max_tools`` tools are returned in a stable order so the agent
        still receives a usable tool set.
        """
        limit = self.max_tools if top_k is None else top_k
        if not self._entries or limit <= 0:
            return []

        scorer = self.score_fn or self._default_score
        query_tokens = _tokenize(intent)
        scored = [
            ScoredTool(spec=entry.spec, score=scorer(query_tokens, entry))
            for entry in self._entries.values()
        ]
        scored.sort(key=lambda s: (-s.score, s.spec.name))

        matched = [s for s in scored if s.score > 0.0]
        if not matched:
            return scored[:limit]

        result = matched[:limit]
        floor = min(self.min_tools, limit, len(scored))
        if len(result) < floor:
            padding = [s for s in scored if s.score == 0.0][: floor - len(result)]
            result = result + padding
        return result

    def select_functions(
        self, intent: str, top_k: Optional[int] = None
    ) -> List[Dict[str, Any]]:
        """Two-pass injection: retrieve top tools, return their OpenAI schemas."""
        return [function_schema(s.spec) for s in self.retrieve(intent, top_k)]

    def selected_names(self, intent: str, top_k: Optional[int] = None) -> List[str]:
        """Names of the tools that would be injected for *intent*."""
        return [s.spec.name for s in self.retrieve(intent, top_k)]


def build_index(tools: Sequence[Any], **kwargs: Any) -> ToolIndexer:
    """Convenience: construct a :class:`ToolIndexer` and index *tools*."""
    indexer = ToolIndexer(**kwargs)
    indexer.index(tools)
    return indexer


__all__ = [
    "ScoreFn",
    "ScoredTool",
    "ToolIndexEntry",
    "ToolIndexer",
    "build_index",
    "function_schema",
]
