package com.jarvis.audit;

import com.jarvis.tools.RiskTier;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Wraps a {@link Tool} so every invocation is written to an {@link AuditLog}. Transparent to the
 * agent loop — same name, description, and result — it just records what ran and how it turned out.
 *
 * <p>The risk tier is supplied at wrap time ({@link RiskTier#UNKNOWN} until plugin manifests declare
 * tiers in the next step). The recorded detail lists the argument <em>names</em> only, never their
 * values, so the audit trail never leaks message bodies, addresses, or secrets.
 */
public final class AuditedTool implements Tool {

    private final Tool delegate;
    private final AuditLog log;
    private final RiskTier riskTier;
    private final AuditTrigger trigger;

    /** Wraps {@code delegate} recording user-triggered invocations at {@link RiskTier#UNKNOWN}. */
    public AuditedTool(Tool delegate, AuditLog log) {
        this(delegate, log, RiskTier.UNKNOWN, AuditTrigger.USER);
    }

    public AuditedTool(Tool delegate, AuditLog log, RiskTier riskTier, AuditTrigger trigger) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.log = Objects.requireNonNull(log, "log");
        this.riskTier = Objects.requireNonNull(riskTier, "riskTier");
        this.trigger = Objects.requireNonNull(trigger, "trigger");
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
        String args = argNames(call);
        try {
            ToolResult result = delegate.execute(call);
            log.record(new AuditEvent(AuditCategory.TOOL_INVOCATION, delegate.name(), trigger,
                    riskTier, result.success() ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE,
                    result.success() ? args : args + "; error: " + result.error()));
            return result;
        } catch (RuntimeException e) {
            log.record(new AuditEvent(AuditCategory.TOOL_INVOCATION, delegate.name(), trigger,
                    riskTier, AuditOutcome.FAILURE, args + "; threw: " + e));
            throw e;
        }
    }

    /** A compact, value-free summary of the call's argument names (sorted for stable output). */
    private static String argNames(ToolCall call) {
        if (call.arguments() == null || call.arguments().isEmpty()) {
            return "args: []";
        }
        return "args: " + new TreeSet<>(call.arguments().keySet());
    }
}
