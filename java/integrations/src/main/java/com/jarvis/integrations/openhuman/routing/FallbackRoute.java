package com.jarvis.integrations.openhuman.routing;

/**
 * One entry in a role's ordered fallback chain: if {@code order 0} fails, try {@code order 1}, etc.
 *
 * @param order position in the chain, lowest tried first ({@code >= 0})
 * @param targetId the provider name (Tier 1) or {@code "openhuman"} (Tier 2) this entry routes to
 * @param tier which tier {@code targetId} belongs to
 */
public record FallbackRoute(int order, String targetId, RouteTier tier) {

    public FallbackRoute {
        if (order < 0) {
            throw new IllegalArgumentException("order must be >= 0, got " + order);
        }
        targetId = targetId == null ? "" : targetId.strip();
        if (targetId.isEmpty()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
        tier = tier == null ? RouteTier.TIER2_OPENHUMAN : tier;
    }
}
