package com.jarvis.integrations.openhuman.routing;

import java.util.List;

/**
 * Tier-2 routing configuration for one orchestration role (Conductor / Orchestrator / Worker).
 * Pure data — no engine logic here; {@code RouteSelector} (routing engine) consumes this.
 *
 * @param role the orchestration role this applies to ({@code conductor}/{@code orchestrator}/
 *     {@code worker}), lower-cased
 * @param failoverEnabled whether Tier-2 failover is permitted for this role at all
 * @param fallbackChain the ordered fallback chain, tried in {@link FallbackRoute#order} order
 * @param timeoutMs per-attempt timeout before treating a call as failed, in milliseconds
 * @param maxRetries how many fallback attempts to make after the primary fails ({@code >= 0})
 */
public record AgentRoleRouteConfig(
        String role,
        boolean failoverEnabled,
        List<FallbackRoute> fallbackChain,
        int timeoutMs,
        int maxRetries) {

    /** Applied when a caller supplies a non-positive timeout. */
    public static final int DEFAULT_TIMEOUT_MS = 15_000;

    public AgentRoleRouteConfig {
        role = role == null ? "" : role.strip().toLowerCase();
        fallbackChain = fallbackChain == null ? List.of() : List.copyOf(fallbackChain);
        timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        maxRetries = Math.max(0, maxRetries);
    }
}
