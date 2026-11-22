"""Dependency-free feature extraction shared by the classifier and similarity routers.

Uses the signed hashing trick (Weinberger et al., 2009) over word unigrams
and character trigrams, plus the existing :func:`score_complexity` signals as
a handful of engineered features. Everything here is pure standard library —
no ``numpy``/``sklearn`` required — so it works in the base install and stays
deterministic across processes (unlike Python's salted ``hash()``).
"""

from __future__ import annotations

import hashlib
import re
from typing import List, Tuple

from openjarvis.learning.routing.complexity import score_complexity

_WORD_RE = re.compile(r"[a-z0-9]+")

#: Hashed bag-of-words/n-gram dimensions. Small on purpose — this is a linear
#: model over a handful of thousand training examples at most, not a deep net.
DEFAULT_HASH_DIM = 64

#: Engineered features appended after the hashed dimensions, in this order.
ENGINEERED_FEATURE_NAMES: Tuple[str, ...] = (
    "length",
    "domain",
    "reasoning",
    "multi_part",
    "creative",
)


def tokenize_words(text: str) -> List[str]:
    """Lowercase, alphanumeric word tokens."""
    return _WORD_RE.findall(text.lower())


def char_trigrams(text: str) -> List[str]:
    """Character trigrams over the lowercased text (robust to code/typos)."""
    lowered = text.lower()
    if len(lowered) < 3:
        return [lowered] if lowered else []
    return [lowered[i : i + 3] for i in range(len(lowered) - 2)]


def _hash_token(token: str, dim: int) -> Tuple[int, float]:
    """Stable (across processes) index + sign for the hashing trick.

    Both are derived from a single blake2b digest so results don't depend on
    ``PYTHONHASHSEED`` (unlike the builtin ``hash()``) — required so a model
    trained in one process can be loaded and produce identical features in
    another.
    """
    digest = hashlib.blake2b(token.encode("utf-8"), digest_size=8).digest()
    value = int.from_bytes(digest, "big")
    idx = value % dim
    sign = 1.0 if (value >> 1) & 1 == 0 else -1.0
    return idx, sign


def hashed_ngram_vector(text: str, dim: int = DEFAULT_HASH_DIM) -> List[float]:
    """Signed hashing-trick vector over word unigrams + character trigrams.

    L2-normalized so vector magnitude doesn't scale with query length.
    """
    vec = [0.0] * dim
    tokens = tokenize_words(text) + char_trigrams(text)
    for tok in tokens:
        idx, sign = _hash_token(tok, dim)
        vec[idx] += sign
    norm = sum(v * v for v in vec) ** 0.5
    if norm > 0:
        vec = [v / norm for v in vec]
    return vec


def featurize(text: str, hash_dim: int = DEFAULT_HASH_DIM) -> List[float]:
    """Full feature vector: hashed n-grams + engineered complexity signals.

    Length is always ``hash_dim + len(ENGINEERED_FEATURE_NAMES)``.
    """
    result = score_complexity(text)
    engineered = [
        result.signals.get("length", 0.0),
        result.signals.get("domain", 0.0),
        result.signals.get("reasoning", 0.0),
        result.signals.get("multi_part", 0.0),
        result.signals.get("creative", 0.0),
    ]
    return hashed_ngram_vector(text, dim=hash_dim) + engineered


def feature_dim(hash_dim: int = DEFAULT_HASH_DIM) -> int:
    """Total feature vector length for a given hashed dimension."""
    return hash_dim + len(ENGINEERED_FEATURE_NAMES)


def cosine_similarity(a: List[float], b: List[float]) -> float:
    """Cosine similarity between two equal-length vectors, in [-1, 1]."""
    if len(a) != len(b) or not a:
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = sum(x * x for x in a) ** 0.5
    norm_b = sum(y * y for y in b) ** 0.5
    if norm_a == 0.0 or norm_b == 0.0:
        return 0.0
    return dot / (norm_a * norm_b)


__all__ = [
    "DEFAULT_HASH_DIM",
    "ENGINEERED_FEATURE_NAMES",
    "char_trigrams",
    "cosine_similarity",
    "feature_dim",
    "featurize",
    "hashed_ngram_vector",
    "tokenize_words",
]
