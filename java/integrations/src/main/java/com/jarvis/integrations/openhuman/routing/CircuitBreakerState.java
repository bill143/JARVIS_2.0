package com.jarvis.integrations.openhuman.routing;

import java.time.Instant;

/**
 * An immutable snapshot of a circuit breaker's lifecycle for one route target. Pure data — the state
 * machine's transition rules (when to flip {@code CLOSED -> OPEN}, when a cooldown elapses into
 * {@code HALF_OPEN}, etc.) live in the routing engine (Stage 3's {@code RouteSelector}), not here.
 *
 * @param phase the current lifecycle phase
 * @param consecutiveFailures failures observed since the last success (reset to 0 on success)
 * @param openedAt when the breaker most recently tripped to {@link Phase#OPEN}, or {@code null} if
 *     it has never tripped
 * @param nextRetryAt the earliest instant a {@link Phase#HALF_OPEN} probe may be attempted, or
 *     {@code null} when not applicable
 */
public record CircuitBreakerState(Phase phase, int consecutiveFailures, Instant openedAt,
        Instant nextRetryAt) {

    /** Circuit breaker lifecycle phases. */
    public enum Phase {
        /** Requests flow normally. */
        CLOSED,
        /** Requests are short-circuited without attempting the target. */
        OPEN,
        /** A single probe request is allowed through to test recovery. */
        HALF_OPEN
    }

    public CircuitBreakerState {
        if (consecutiveFailures < 0) {
            throw new IllegalArgumentException("consecutiveFailures must be >= 0");
        }
        phase = phase == null ? Phase.CLOSED : phase;
    }

    /** The initial state for a target that has never failed. */
    public static CircuitBreakerState closed() {
        return new CircuitBreakerState(Phase.CLOSED, 0, null, null);
    }
}
