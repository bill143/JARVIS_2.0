"""PII detection + redaction and secret masking for responses/logs."""

from __future__ import annotations

import re

from jarvis_shared.redaction import mask_secrets

_PII_PATTERNS: dict[str, re.Pattern] = {
    "email": re.compile(r"\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b"),
    "ssn": re.compile(r"\b\d{3}-\d{2}-\d{4}\b"),
    "credit_card": re.compile(r"\b(?:\d[ -]?){13,16}\b"),
    "phone": re.compile(r"\b(?:\+?1[ -]?)?\(?\d{3}\)?[ -]?\d{3}[ -]?\d{4}\b"),
    "ipv4": re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b"),
}

_REDACTIONS = {
    "email": "[REDACTED_EMAIL]",
    "ssn": "[REDACTED_SSN]",
    "credit_card": "[REDACTED_CC]",
    "phone": "[REDACTED_PHONE]",
    "ipv4": "[REDACTED_IP]",
}


def detect_pii(text: str) -> dict[str, int]:
    """Return {pii_type: match_count} for any PII present."""
    if not text:
        return {}
    found: dict[str, int] = {}
    for name, pattern in _PII_PATTERNS.items():
        matches = pattern.findall(text)
        if matches:
            found[name] = len(matches)
    return found


def redact_pii(text: str, types: set[str] | None = None) -> str:
    """Redact PII in text. `types` limits which categories are redacted."""
    if not text:
        return text
    for name, pattern in _PII_PATTERNS.items():
        if types is None or name in types:
            text = pattern.sub(_REDACTIONS[name], text)
    return text


def redact_secrets(text: str) -> str:
    """Mask secret material (delegates to the shared masker)."""
    return mask_secrets(text)
