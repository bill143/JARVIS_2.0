package com.jarvis.audit;

import com.jarvis.tools.RiskTier;
import java.util.Objects;

/**
 * One thing worth recording: a tool call, a destructive action, or an external API call. The
 * timestamp is not part of the event — {@link AuditLog} assigns it on record, so an event is a pure
 * description of <em>what</em> happened, independent of <em>when</em>.
 *
 * @param category what kind of activity this is
 * @param action the short name of what ran (usually a tool name, e.g. {@code email_trash})
 * @param trigger what caused it (user command vs. autonomous step)
 * @param riskTier how dangerous it is ({@link RiskTier#UNKNOWN} until manifests declare tiers)
 * @param outcome whether it succeeded
 * @param detail a short human-readable summary (arguments, target, or error message)
 */
public record AuditEvent(
        AuditCategory category,
        String action,
        AuditTrigger trigger,
        RiskTier riskTier,
        AuditOutcome outcome,
        String detail) {

    public AuditEvent {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(trigger, "trigger");
        riskTier = riskTier == null ? RiskTier.UNKNOWN : riskTier;
        Objects.requireNonNull(outcome, "outcome");
        detail = detail == null ? "" : detail;
    }

    /** A successful user-triggered tool invocation with the given detail. */
    public static AuditEvent toolSuccess(String action, RiskTier tier, String detail) {
        return new AuditEvent(AuditCategory.TOOL_INVOCATION, action, AuditTrigger.USER, tier,
                AuditOutcome.SUCCESS, detail);
    }

    /** A failed user-triggered tool invocation with the given detail. */
    public static AuditEvent toolFailure(String action, RiskTier tier, String detail) {
        return new AuditEvent(AuditCategory.TOOL_INVOCATION, action, AuditTrigger.USER, tier,
                AuditOutcome.FAILURE, detail);
    }
}
