package com.jarvis.integrations.openhuman.routing;

/**
 * A structural, audit-ready snapshot of one routing decision — the fields a Tier-2 routing audit
 * event needs, independent of the {@code AuditLog}/{@code AuditEvent} types (which live in the
 * {@code audit-log} module, not a dependency of this one). The routing engine (Stage 3) produces
 * these; the caller (app-module wiring, Stage 4) turns them into audit events.
 *
 * @param role the orchestration role the decision was made for
 * @param selectedTier which tier was ultimately used
 * @param selectedTarget the provider name (Tier 1) or {@code "openhuman"} (Tier 2) that was called
 * @param reasonCode why this route was chosen (never {@code null})
 * @param latencyMs how long the selected call took, in milliseconds ({@code -1} if never attempted,
 *     e.g. {@link ReasonCode#CIRCUIT_OPEN})
 * @param success whether the selected call succeeded
 */
public record RouteDecision(
        String role,
        RouteTier selectedTier,
        String selectedTarget,
        ReasonCode reasonCode,
        long latencyMs,
        boolean success) {

    public RouteDecision {
        role = role == null ? "" : role.strip().toLowerCase();
        selectedTarget = selectedTarget == null ? "" : selectedTarget.strip();
        reasonCode = reasonCode == null ? ReasonCode.NO_ROUTE_CONFIGURED : reasonCode;
    }

    /** Why a particular route was selected (or why routing failed outright). */
    public enum ReasonCode {
        /** The Tier-1 primary answered normally. */
        PRIMARY_OK,
        /** The Tier-1 primary exceeded its configured timeout. */
        PRIMARY_TIMEOUT,
        /** The Tier-1 primary returned HTTP 429. */
        PRIMARY_RATE_LIMITED,
        /** The Tier-1 primary returned an HTTP 5xx. */
        PRIMARY_SERVER_ERROR,
        /** The Tier-1 primary returned an unparseable/malformed response. */
        PRIMARY_MALFORMED_RESPONSE,
        /** An explicit policy match (e.g. {@code task_type=memory_consult}) routed straight to Tier 2. */
        POLICY_MEMORY_CONSULT,
        /** The target's circuit breaker was open, so it was skipped without an attempt. */
        CIRCUIT_OPEN,
        /** Tier-2 failover is disabled for this role, so no fallback was attempted. */
        FAILOVER_DISABLED,
        /** No route (primary or fallback) was configured for this role. */
        NO_ROUTE_CONFIGURED,
        /** Every candidate in the fallback chain (primary and all fallbacks) failed. */
        ALL_ROUTES_EXHAUSTED
    }
}
