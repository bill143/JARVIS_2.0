"""Shared token counting for every context budget in OpenJarvis.

Context injection, session memory and the compactor all enforce token budgets.
They previously used three different estimators (whitespace split, a
duplicate whitespace split, and chars/4), so the same text could be "512
tokens" to one budget and "700" to another. Whitespace splitting also
undercounts real BPE tokens by roughly 25-40% on English prose and far more on
code, URLs and JSON, which lets injected context silently overrun its budget.

:func:`count_tokens` is the single counter they now share:

- With ``tiktoken`` installed (``pip install openjarvis[context-tiktoken]``),
  it returns the exact ``cl100k_base`` token count. That is the GPT-4 family
  tokenizer and a close, slightly conservative proxy for Claude and other
  modern BPE vocabularies.
- Without it, it falls back to the dependency-free ``ceil(chars / 4)``
  heuristic, which tracks BPE counts far better than word counts.
"""

from __future__ import annotations

import functools
import logging
import math
from typing import Callable, Optional

logger = logging.getLogger(__name__)

#: Characters per token used by the dependency-free fallback.
CHARS_PER_TOKEN = 4

_ENCODING_NAME = "cl100k_base"


@functools.lru_cache(maxsize=1)
def _tiktoken_encoder() -> Optional[Callable[[str], list]]:
    """Return a cached ``cl100k_base`` encode function, or ``None``.

    ``None`` means ``tiktoken`` is not installed or its encoding could not be
    loaded (for example, no network on first use to fetch the BPE file). The
    failure is logged once, and the heuristic is used from then on.
    """
    try:
        import tiktoken
    except ImportError:
        logger.debug(
            "tiktoken not installed; token counts use the chars/%d heuristic",
            CHARS_PER_TOKEN,
        )
        return None
    try:
        encoding = tiktoken.get_encoding(_ENCODING_NAME)
    except Exception as exc:  # network / cache failures surface as many types
        logger.warning(
            "tiktoken could not load %s (%s); token counts use the chars/%d heuristic",
            _ENCODING_NAME,
            exc,
            CHARS_PER_TOKEN,
        )
        return None
    # disallowed_special=() so user text containing "<|endoftext|>" is counted
    # as ordinary text instead of raising.
    return functools.partial(encoding.encode, disallowed_special=())


def heuristic_token_count(text: str) -> int:
    """Estimate tokens in *text* as ``ceil(len(text) / 4)``; 0 for empty."""
    if not text:
        return 0
    return math.ceil(len(text) / CHARS_PER_TOKEN)


def count_tokens(text: str) -> int:
    """Count tokens in *text*: exact with ``tiktoken``, else the heuristic."""
    if not text:
        return 0
    encode = _tiktoken_encoder()
    if encode is None:
        return heuristic_token_count(text)
    return len(encode(text))


def tiktoken_available() -> bool:
    """Whether :func:`count_tokens` is using the exact tokenizer."""
    return _tiktoken_encoder() is not None


__all__ = [
    "CHARS_PER_TOKEN",
    "count_tokens",
    "heuristic_token_count",
    "tiktoken_available",
]
