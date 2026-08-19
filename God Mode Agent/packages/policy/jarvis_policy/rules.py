"""Argument risk classification + destination/path checks used by the engine."""

from __future__ import annotations

import re
from urllib.parse import urlparse

# Tools considered high-risk -> require approval unless explicitly allowed.
HIGH_RISK_TOOLS = {"python_exec", "file_write"}

_URL_RE = re.compile(r"https?://[^\s\"'<>]+", re.IGNORECASE)
_DANGEROUS_CODE = re.compile(
    r"(?i)\b(import\s+(os|sys|subprocess|socket|shutil|ctypes|requests|urllib))\b"
    r"|__import__|eval\(|exec\(|open\(|compile\(|subprocess|socket\.|os\.system"
)


def extract_urls(text: str) -> list[str]:
    return _URL_RE.findall(text or "")


def domain_of(url: str) -> str:
    try:
        return (urlparse(url).hostname or "").lower()
    except ValueError:
        return ""


def domain_allowed(domain: str, allowlist: list[str]) -> bool:
    if not domain:
        return False
    for allowed in allowlist:
        if domain == allowed or domain.endswith("." + allowed):
            return True
    return False


def classify_arguments(tool: str, arguments: dict, settings) -> dict:
    """Return a risk report: {risk, reasons[], domains[], blocked_domains[], paths[]}."""
    reasons: list[str] = []
    risk = "low"
    domains: list[str] = []
    blocked_domains: list[str] = []
    paths: list[str] = []

    blob = " ".join(str(v) for v in arguments.values())

    # Destination domains (web tools or any URL in args).
    for url in extract_urls(blob):
        d = domain_of(url)
        domains.append(d)
        if not domain_allowed(d, settings.domain_allowlist):
            blocked_domains.append(d)
            reasons.append(f"domain '{d}' not in allowlist")
            risk = "high"

    if tool == "web_search":
        # search query text isn't a fetch, but flag exfil-looking queries
        if re.search(r"(?i)\b(api[_ ]?key|password|secret|token)\b", blob):
            reasons.append("search query references secrets")
            risk = "medium"

    if tool == "file_write":
        path = str(arguments.get("path", ""))
        paths.append(path)
        ext = ("." + path.rsplit(".", 1)[-1]).lower() if "." in path else ""
        if ext and ext not in settings.file_write_extension_set:
            reasons.append(f"file extension '{ext}' not permitted")
            risk = "high"
        if re.search(r"(?i)\.(exe|dll|bat|ps1|sh|cmd|scr|com)$", path):
            reasons.append("executable file extension blocked")
            risk = "high"

    if tool == "file_read":
        paths.append(str(arguments.get("path", "")))

    if tool == "python_exec":
        code = str(arguments.get("code", ""))
        if _DANGEROUS_CODE.search(code):
            reasons.append("code references blocked imports/builtins")
            risk = "high"
        else:
            risk = "medium" if risk == "low" else risk

    return {"risk": risk, "reasons": reasons, "domains": domains, "blocked_domains": blocked_domains, "paths": paths}
