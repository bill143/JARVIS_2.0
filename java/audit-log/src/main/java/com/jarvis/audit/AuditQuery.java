package com.jarvis.audit;

import com.jarvis.tools.RiskTier;
import java.time.Instant;
import java.util.Locale;

/**
 * A filter over audit entries. Every field is nullable — a null field means "don't filter on this".
 * Drives the Audit Log page's tool / date / risk-tier filters. Build one fluently:
 * {@code AuditQuery.all().action("email").riskTier(RiskTier.DESTRUCTIVE)}.
 *
 * @param action case-insensitive substring matched against the entry's action (null = any)
 * @param category exact category to match (null = any)
 * @param riskTier exact risk tier to match (null = any)
 * @param trigger exact trigger to match (null = any)
 * @param outcome exact outcome to match (null = any)
 * @param from inclusive lower time bound (null = open)
 * @param to inclusive upper time bound (null = open)
 */
public record AuditQuery(
        String action,
        AuditCategory category,
        RiskTier riskTier,
        AuditTrigger trigger,
        AuditOutcome outcome,
        Instant from,
        Instant to) {

    /** An empty query that matches every entry. */
    public static AuditQuery all() {
        return new AuditQuery(null, null, null, null, null, null, null);
    }

    public AuditQuery action(String value) {
        return new AuditQuery(value, category, riskTier, trigger, outcome, from, to);
    }

    public AuditQuery category(AuditCategory value) {
        return new AuditQuery(action, value, riskTier, trigger, outcome, from, to);
    }

    public AuditQuery riskTier(RiskTier value) {
        return new AuditQuery(action, category, value, trigger, outcome, from, to);
    }

    public AuditQuery trigger(AuditTrigger value) {
        return new AuditQuery(action, category, riskTier, value, outcome, from, to);
    }

    public AuditQuery outcome(AuditOutcome value) {
        return new AuditQuery(action, category, riskTier, trigger, value, from, to);
    }

    public AuditQuery from(Instant value) {
        return new AuditQuery(action, category, riskTier, trigger, outcome, value, to);
    }

    public AuditQuery to(Instant value) {
        return new AuditQuery(action, category, riskTier, trigger, outcome, from, value);
    }

    /** Whether {@code entry} satisfies every set field of this query. */
    public boolean matches(AuditEntry entry) {
        AuditEvent e = entry.event();
        if (action != null && !action.isBlank()
                && !e.action().toLowerCase(Locale.ROOT).contains(action.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (category != null && e.category() != category) {
            return false;
        }
        if (riskTier != null && e.riskTier() != riskTier) {
            return false;
        }
        if (trigger != null && e.trigger() != trigger) {
            return false;
        }
        if (outcome != null && e.outcome() != outcome) {
            return false;
        }
        if (from != null && entry.at().isBefore(from)) {
            return false;
        }
        return to == null || !entry.at().isAfter(to);
    }
}
