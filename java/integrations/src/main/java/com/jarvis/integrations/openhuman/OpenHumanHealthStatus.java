package com.jarvis.integrations.openhuman;

/**
 * The result of an OpenHuman {@code GET /health} probe. Verified against the OpenHuman core source:
 * the endpoint returns {@code 503} only when a component in its {@code CRITICAL_COMPONENTS} set
 * ({@code core}, {@code memory_tree_db}) is unhealthy; any other component failing still returns
 * {@code 200} with {@code degraded: true} in the body. {@code reachable} reflects the former (a
 * critical failure or a network/config problem makes the core unusable); {@code degraded} surfaces
 * the latter so a caller (e.g. a Tier-2 circuit breaker) can distinguish "down" from "up but
 * struggling".
 *
 * @param reachable whether the core responded and is not in a critical-failure state
 * @param degraded whether a non-critical component is unhealthy (only meaningful when reachable)
 * @param httpStatus the raw HTTP status code, or {@code 0} when the transport was not configured
 */
public record OpenHumanHealthStatus(boolean reachable, boolean degraded, int httpStatus) {

    /** The core has no base URL/token configured — no request was made. */
    public static final OpenHumanHealthStatus NOT_CONFIGURED = new OpenHumanHealthStatus(false, false, 0);
}
