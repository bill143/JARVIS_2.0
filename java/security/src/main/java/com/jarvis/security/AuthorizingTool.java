package com.jarvis.security;

import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.tools.RiskTier;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Wraps a {@link Tool} with a permission gate. When the {@link PermissionPolicy} says an action at
 * this tool's {@link RiskTier} needs confirmation, it asks the user (via a {@link PermissionGate})
 * and blocks until they answer. Approved actions run; denied or timed-out actions return a graceful
 * error and are never executed. Every confirmation decision is written to the {@link AuditLog}.
 *
 * <p>Read-only tools (and everything, when prompting is OFF) pass straight through with no prompt
 * and no extra audit entry — the underlying execution audit already covers them.
 */
public final class AuthorizingTool implements Tool {

    private final Tool delegate;
    private final RiskTier riskTier;
    private final PermissionPolicy policy;
    private final PermissionGate gate;
    private final AuditLog auditLog;

    public AuthorizingTool(Tool delegate, RiskTier riskTier, PermissionPolicy policy,
            PermissionGate gate, AuditLog auditLog) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.riskTier = riskTier == null ? RiskTier.UNKNOWN : riskTier;
        this.policy = Objects.requireNonNull(policy, "policy");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if (policy.decide(riskTier) == PermissionDecision.ALLOW) {
            return delegate.execute(call);
        }
        PermissionOutcome outcome = gate.request(delegate.name(), riskTier, argNames(call));
        auditLog.record(new AuditEvent(AuditCategory.SYSTEM, delegate.name(), AuditTrigger.USER,
                riskTier, outcome == PermissionOutcome.ALLOWED ? AuditOutcome.SUCCESS
                        : AuditOutcome.FAILURE, "permission prompt -> " + outcome));
        if (outcome == PermissionOutcome.ALLOWED) {
            return delegate.execute(call);
        }
        return ToolResult.error("Permission " + phrase(outcome) + " for " + delegate.name());
    }

    private static String phrase(PermissionOutcome outcome) {
        return outcome == PermissionOutcome.TIMED_OUT ? "request timed out" : "denied by the user";
    }

    private static String argNames(ToolCall call) {
        if (call.arguments() == null || call.arguments().isEmpty()) {
            return "args: []";
        }
        return "args: " + new TreeSet<>(call.arguments().keySet());
    }
}
