"""Chunking strategies: fixed-size (overlapping) and semantic (paragraph/sentence)."""

from __future__ import annotations

import re


def chunk_fixed(text: str, size: int = 400, overlap: int = 60) -> list[str]:
    text = text.strip()
    if not text:
        return []
    chunks = []
    start = 0
    while start < len(text):
        end = min(start + size, len(text))
        chunks.append(text[start:end])
        if end >= len(text):
            break
        start = end - overlap
    return chunks


def chunk_semantic(text: str, max_chars: int = 600) -> list[str]:
    """Group paragraphs/sentences into chunks bounded by max_chars."""
    paras = [p.strip() for p in re.split(r"\n\s*\n", text) if p.strip()]
    if not paras:
        paras = [text.strip()]
    chunks: list[str] = []
    buf = ""
    for para in paras:
        if len(buf) + len(para) + 1 <= max_chars:
            buf = (buf + "\n" + para).strip()
        else:
            if buf:
                chunks.append(buf)
            if len(para) <= max_chars:
                buf = para
            else:
                # sentence-split oversized paragraphs
                for sent in re.split(r"(?<=[.!?])\s+", para):
                    if len(buf) + len(sent) + 1 <= max_chars:
                        buf = (buf + " " + sent).strip()
                    else:
                        if buf:
                            chunks.append(buf)
                        buf = sent
    if buf:
        chunks.append(buf)
    return chunks


def chunk(text: str, strategy: str = "semantic", **kwargs) -> list[str]:
    if strategy == "fixed":
        return chunk_fixed(text, **kwargs)
    return chunk_semantic(text, **kwargs)
