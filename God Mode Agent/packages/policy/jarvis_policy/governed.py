"""GovernedToolRegistry: wraps the Phase 1 ToolRegistry with policy + audit.

Every execute() call: evaluate policy -> (allow | deny | require_approval |
redact_and_allow) -> run tool (with tenant scoping) -> audit the decision and
outcome. require_approval raises a structured error carrying an approval id.
"""

from __future__ import annotations

from jarvis_policy.engine import PolicyDecision, PolicyEngine
from jarvis_safety.pii import redact_pii, redact_secrets
from jarvis_shared.errors import PolicyDenied
from jarvis_shared.logging import get_logger, log_event

logger = get_logger("jarvis.policy")


class ApprovalRequired(PolicyDenied):
    code = "APPROVAL_REQUIRED"
    http_status = 202

    def __init__(self, message: str, approval_id: str, decision: dict):
        super().__init__(message)
        self.approval_id = approval_id
        self.decision = decision


class GovernedToolRegistry:
    def __init__(self, registry, engine: PolicyEngine, approvals, audit, metrics=None):
        self.registry = registry
        self.engine = engine
        self.approvals = approvals
        self.audit = audit
        self.metrics = metrics

    def names(self) -> list[str]:
        return self.registry.names()

    def schemas(self) -> list[dict]:
        return self.registry.schemas()

    def evaluate(self, principal, tool: str, arguments: dict) -> PolicyDecision:
        return self.engine.evaluate(principal, tool, arguments)

    async def execute(self, principal, tool: str, arguments: dict, session_id: str = "default", *, bypass_policy: bool = False):
        arguments = arguments or {}
        decision = self.engine.evaluate(principal, tool, arguments) if not bypass_policy else PolicyDecision("allow", "bypass", tool)

        self.audit.record(
            "policy", f"tool:{tool}", actor=principal.username, tenant=principal.tenant,
            detail={"decision": decision.action, "reason": decision.reason, "risk": decision.risk, "labels": decision.labels},
        )
        if self.metrics:
            self.metrics.counter("jarvis_policy_decisions_total", labels={"action": decision.action, "tool": tool},
                                 help="Policy decisions by action")

        if decision.action == "deny":
            log_event(logger, "policy.deny", tool=tool, actor=principal.username, reason=decision.reason)
            raise PolicyDenied(decision.reason, code="POLICY_DENIED")

        if decision.action == "require_approval":
            approval = self.approvals.create(
                requester=principal.username, tenant=principal.tenant, tool=tool,
                arguments=arguments, reason=decision.reason,
            )
            self.audit.record("approval", "requested", actor=principal.username, tenant=principal.tenant,
                              detail={"approval_id": approval["id"], "tool": tool})
            log_event(logger, "policy.require_approval", tool=tool, approval_id=approval["id"])
            raise ApprovalRequired(
                f"approval required for '{tool}': {decision.reason}", approval["id"], decision.as_dict()
            )

        result = await self.registry.execute(tool, arguments, session_id=session_id)

        if decision.action == "redact_and_allow" and isinstance(result.output, dict):
            result.output = self._redact_output(result.output)

        self.audit.record(
            "tool", f"executed:{tool}", actor=principal.username, tenant=principal.tenant,
            detail={"status": result.status, "duration_ms": result.duration_ms, "decision": decision.action},
        )
        return result, decision

    async def execute_approved(self, approval: dict, session_id: str = "approved"):
        """Run a tool call whose approval has been granted."""
        result = await self.registry.execute(approval["tool"], approval["arguments"], session_id=session_id)
        self.audit.record("tool", f"executed_approved:{approval['tool']}", actor=approval["requester"],
                          tenant=approval["tenant"], detail={"status": result.status, "approval_id": approval["id"]})
        return result

    @staticmethod
    def _redact_output(output: dict) -> dict:
        # Deep redaction: serialize, mask secrets + PII across the whole structure
        # (including nested lists/dicts), then parse back.
        import json

        masked = redact_pii(redact_secrets(json.dumps(output, default=str)))
        try:
            return json.loads(masked)
        except json.JSONDecodeError:
            return {"redacted": masked}
