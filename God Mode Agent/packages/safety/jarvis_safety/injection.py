"""Heuristic prompt-injection / exfiltration classifier with risk scoring."""

from __future__ import annotations

import re
from dataclasses import dataclass, field

# (weight, compiled pattern, label)
_SIGNALS: list[tuple[float, re.Pattern, str]] = [
    (0.45, re.compile(r"(?i)\bignore (all |any |the )?(previous|prior|above) (instructions|prompts?|rules)"), "ignore_previous"),
    (0.45, re.compile(r"(?i)\bdisregard (all |the )?(previous|prior|earlier|above)\b"), "disregard"),
    (0.5, re.compile(r"(?i)\b(reveal|show|print|repeat|leak|expose|tell|give|send)\b.{0,20}\b(system|initial|hidden) prompt"), "system_prompt_leak"),
    (0.35, re.compile(r"(?i)\b(system|initial|hidden) prompt\b"), "prompt_reference"),
    (0.5, re.compile(r"(?i)\byou are now\b|\bnew (instructions|persona|role)\b|\bact as\b"), "role_override"),
    (0.55, re.compile(r"(?i)\b(developer|dev|jailbreak|DAN) mode\b"), "jailbreak_mode"),
    (0.5, re.compile(r"(?i)\b(print|reveal|exfiltrate|send|leak|dump|show|expose)\b.{0,25}\b(env|environment|secret|api[_ ]?key|password|token|credential|\.env)"), "secret_extraction"),
    (0.4, re.compile(r"(?i)\bexfiltrat|data ?exfil|send .* to (http|https|ftp)"), "exfiltration"),
    (0.35, re.compile(r"(?i)\boverride (the )?(policy|safety|guardrail|filter)"), "policy_override"),
    (0.3, re.compile(r"(?i)\bbase64\b.*\b(decode|encode)\b.*(secret|key|token)"), "encoded_secret"),
    (0.3, re.compile(r"(?i)\bcurl\b.*\b(env|secret|key)|wget .*(key|token)"), "shell_exfil"),
]

# Note: no \b around .env (word boundary fails against a leading dot), and
# 'credential' is matched with an optional trailing 's'.
_SECRET_TARGET = re.compile(
    r"(?i)(api[_ ]?key|secret|password|token|credentials?|\.env|OPENAI_API_KEY|ANTHROPIC_API_KEY)"
)


@dataclass
class InjectionVerdict:
    score: float
    blocked: bool
    labels: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {"score": round(self.score, 3), "blocked": self.blocked, "labels": self.labels}


def classify_injection(text: str, threshold: float = 0.7) -> InjectionVerdict:
    """Return a risk score in [0,1], matched labels, and a block decision."""
    if not text:
        return InjectionVerdict(score=0.0, blocked=False)
    score = 0.0
    labels: list[str] = []
    for weight, pattern, label in _SIGNALS:
        if pattern.search(text):
            score += weight
            labels.append(label)
    # Bonus when an instruction-override co-occurs with a secret target: the
    # combination (manipulation + secret reference) is a strong exfiltration signal.
    if labels and _SECRET_TARGET.search(text):
        score += 0.35
        labels.append("targets_secret")
    score = min(score, 1.0)
    return InjectionVerdict(score=score, blocked=score >= threshold, labels=labels)
