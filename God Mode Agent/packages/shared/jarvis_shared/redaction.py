"""Secret masking applied to every log line, error envelope, and audit detail."""

from __future__ import annotations

import re

_PATTERNS: list[tuple[re.Pattern, str]] = [
    # OpenAI-style keys
    (re.compile(r"sk-[A-Za-z0-9_\-]{10,}"), "sk-***"),
    # JARVIS machine API keys
    (re.compile(r"jk_[A-Za-z0-9_\-]{10,}"), "jk_***"),
    # Bearer tokens / JWTs
    (re.compile(r"(?i)\b(bearer)\s+[A-Za-z0-9\-_.=]{10,}"), r"\1 ***"),
    (re.compile(r"\beyJ[A-Za-z0-9\-_]{10,}\.[A-Za-z0-9\-_]{5,}\.[A-Za-z0-9\-_]{5,}\b"), "jwt.***"),
    # key=value / key: value style secrets
    (
        re.compile(r"(?i)\b(api[_-]?key|apikey|secret|password|passwd|token|authorization|x-api-key)(\"?\s*[:=]\s*\"?)([^\s\"',;{}]{4,})"),
        r"\1\2***",
    ),
]


def mask_secrets(text: str) -> str:
    """Replace likely secret material with *** while keeping the line readable."""
    if not isinstance(text, str) or not text:
        return text
    for pattern, repl in _PATTERNS:
        text = pattern.sub(repl, text)
    return text
