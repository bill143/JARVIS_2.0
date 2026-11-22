"""Policy engine: evaluate every tool call and emit an auditable decision.

Inputs: role, tenant/user, tool name, argument risk, destination domains,
filesystem paths. Actions: allow | deny | require_approval | redact_and_allow.
Rules can be seeded from the DB (policy_rules) and org/user allow/deny lists.
"""

from __future__ import annotations

import sqlite3
import threading
from dataclasses import dataclass, field
from pathlib import Path

from jarvis_auth.rbac import role_at_least
from jarvis_policy.rules import HIGH_RISK_TOOLS, classify_arguments
from jarvis_safety.injection import classify_injection

VALID_ACTIONS = {"allow", "deny", "require_approval", "redact_and_allow"}


@dataclass
class PolicyDecision:
    action: str
    reason: str
    tool: str
    risk: str = "low"
    rule_id: str = ""
    labels: list[str] = field(default_factory=list)
    details: dict = field(default_factory=dict)

    def as_dict(self) -> dict:
        return {
            "action": self.action, "reason": self.reason, "tool": self.tool,
            "risk": self.risk, "rule_id": self.rule_id, "labels": self.labels, "details": self.details,
        }


class PolicyEngine:
    def __init__(self, settings, db_path: Path | None = None):
        self.settings = settings
        self.default_action = settings.policy_default_action
        self._lock = threading.Lock()
        self._db_path = db_path or settings.sqlite_path
        self.conn = sqlite3.connect(str(self._db_path), check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        self._ensure_schema()

    def _ensure_schema(self) -> None:
        with self._lock:
            self.conn.execute(
                """
                CREATE TABLE IF NOT EXISTS policy_rules (
                    id TEXT PRIMARY KEY, scope TEXT NOT NULL DEFAULT 'org', subject TEXT NOT NULL DEFAULT '*',
                    tool TEXT NOT NULL DEFAULT '*', action TEXT NOT NULL DEFAULT 'deny',
                    priority INTEGER NOT NULL DEFAULT 100, note TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL
                )
                """
            )
            self.conn.commit()

    def list_rules(self) -> list[dict]:
        with self._lock:
            rows = self.conn.execute(
                "SELECT id, scope, subject, tool, action, priority, note FROM policy_rules ORDER BY priority ASC"
            ).fetchall()
        return [
            {"id": r[0], "scope": r[1], "subject": r[2], "tool": r[3], "action": r[4], "priority": r[5], "note": r[6]}
            for r in rows
        ]

    def add_rule(self, rule_id: str, scope: str, subject: str, tool: str, action: str, priority: int, note: str = "") -> None:
        if action not in VALID_ACTIONS:
            raise ValueError(f"invalid action '{action}'")
        from datetime import UTC, datetime

        with self._lock:
            self.conn.execute(
                "INSERT OR REPLACE INTO policy_rules (id, scope, subject, tool, action, priority, note, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (rule_id, scope, subject, tool, action, priority, note, datetime.now(UTC).isoformat()),
            )
            self.conn.commit()

    def delete_rule(self, rule_id: str) -> None:
        with self._lock:
            self.conn.execute("DELETE FROM policy_rules WHERE id = ?", (rule_id,))
            self.conn.commit()

    def _matching_rule(self, principal, tool: str) -> dict | None:
        subject_matches = ("*", principal.role, principal.user_id, principal.tenant)
        for rule in self.list_rules():  # already priority-ordered
            if rule["tool"] not in ("*", tool):
                continue
            if rule["subject"] in subject_matches:
                return rule
        return None

    def evaluate(self, principal, tool: str, arguments: dict) -> PolicyDecision:
        """Evaluate a single tool call and return an auditable decision."""
        arguments = arguments or {}

        # 1. readonly role may never invoke mutating/high-risk tools.
        if not role_at_least(principal.role, "user") and tool in HIGH_RISK_TOOLS:
            return PolicyDecision("deny", f"role '{principal.role}' cannot invoke high-risk tool", tool, risk="high")

        # 2. injection scan of argument content.
        blob = " ".join(str(v) for v in arguments.values())
        verdict = classify_injection(blob, threshold=self.settings.injection_block_threshold)
        if verdict.blocked:
            return PolicyDecision(
                "deny", "argument content flagged as prompt-injection/exfiltration", tool,
                risk="high", labels=verdict.labels, details={"injection_score": verdict.score},
            )

        # 3. argument risk classification (domains, paths, code).
        risk_report = classify_arguments(tool, arguments, self.settings)

        # 4. explicit DB rule (highest priority wins).
        rule = self._matching_rule(principal, tool)
        if rule:
            action = rule["action"]
            if action == "deny":
                return PolicyDecision("deny", f"denied by rule {rule['id']}", tool, risk=risk_report["risk"], rule_id=rule["id"], details=risk_report)
            if action == "require_approval":
                return PolicyDecision("require_approval", f"approval required by rule {rule['id']}", tool, risk=risk_report["risk"], rule_id=rule["id"], details=risk_report)
            if action == "redact_and_allow":
                return PolicyDecision("redact_and_allow", f"allowed with redaction by rule {rule['id']}", tool, risk=risk_report["risk"], rule_id=rule["id"], details=risk_report)
            # allow falls through to risk checks below (explicit allow still respects hard blocks)

        # 5. hard blocks from risk classification.
        if risk_report["blocked_domains"]:
            return PolicyDecision("deny", f"blocked domains: {risk_report['blocked_domains']}", tool, risk="high", details=risk_report)
        if risk_report["risk"] == "high" and not (rule and rule["action"] == "allow"):
            return PolicyDecision("require_approval", "; ".join(risk_report["reasons"]) or "high-risk arguments", tool, risk="high", details=risk_report)

        # 6. high-risk tools require approval by default for non-operators.
        if tool in HIGH_RISK_TOOLS and not role_at_least(principal.role, "operator") and not (rule and rule["action"] == "allow"):
            return PolicyDecision("require_approval", f"'{tool}' requires operator role or approval", tool, risk=risk_report["risk"], details=risk_report)

        # 7. explicit allow rule, or fall through to default action.
        if rule and rule["action"] == "allow":
            return PolicyDecision("allow", f"allowed by rule {rule['id']}", tool, risk=risk_report["risk"], rule_id=rule["id"], details=risk_report)

        if self.default_action == "allow":
            return PolicyDecision("allow", "default allow", tool, risk=risk_report["risk"], details=risk_report)
        # POLICY_DEFAULT_ACTION=deny, but low/medium-risk known tools for authorized users are allowed;
        # this keeps Phase 1 chat working while the default stays deny for unknown/high-risk cases.
        if risk_report["risk"] in ("low", "medium") and role_at_least(principal.role, "user"):
            return PolicyDecision("allow", "authorized user, acceptable risk", tool, risk=risk_report["risk"], details=risk_report)
        return PolicyDecision("deny", "default deny", tool, risk=risk_report["risk"], details=risk_report)

    def close(self) -> None:
        with self._lock:
            self.conn.close()
