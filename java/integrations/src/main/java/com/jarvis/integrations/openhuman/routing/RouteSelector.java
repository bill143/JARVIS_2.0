package com.jarvis.integrations.openhuman.routing;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Tier-2 routing engine: given a role's {@link AgentRoleRouteConfig}, evaluates the primary
 * route (the fallback chain's {@code order 0} entry) and, on failure, walks the remaining chain in
 * order — each candidate gated by its own circuit breaker. Deliberately standalone: nothing here
 * calls {@code OrchestrationService} or any live provider; each attempt is delegated to a
 * caller-supplied {@link RouteExecutor}, so this class is fully unit-testable and carries no network
 * code of its own. Wiring it into the primary orchestration flow is a later stage.
 *
 * <p><b>{@code reasonCode} contract:</b> it always explains why the Tier-1 primary was not used (or
 * {@code PRIMARY_OK} if it was) — {@code success} and {@code selectedTier}/{@code selectedTarget}
 * describe the actual final outcome separately. If every candidate in the chain fails, {@code
 * reasonCode} is overridden to {@code ALL_ROUTES_EXHAUSTED} since "why we left primary" is no longer
 * the most useful fact.
 *
 * <p>Thread-safe: circuit breaker state is kept per target id behind a per-target lock, so concurrent
 * {@link #select} calls (for the same or different targets) do not race.
 */
public final class RouteSelector {

    /** Attempts one candidate route and reports what happened — the seam a caller implements. */
    @FunctionalInterface
    public interface RouteExecutor {
        AttemptOutcome execute(String targetId, RouteTier tier);
    }

    /** Why an attempt failed. Mirrors the Tier-2 failover triggers: timeouts, 429s, 5xxs, malformed. */
    public enum FailureKind {
        TIMEOUT, RATE_LIMITED, SERVER_ERROR, MALFORMED_RESPONSE, OTHER
    }

    /** The result of one {@link RouteExecutor} call. */
    public record AttemptOutcome(boolean success, FailureKind failureKind, long latencyMs) {

        public AttemptOutcome {
            if (latencyMs < 0) {
                throw new IllegalArgumentException("latencyMs must be >= 0");
            }
            if (!success) {
                Objects.requireNonNull(failureKind, "failureKind is required when success=false");
            }
        }

        /** A successful attempt that took {@code latencyMs}. */
        public static AttemptOutcome ok(long latencyMs) {
            return new AttemptOutcome(true, null, latencyMs);
        }

        /** A failed attempt of the given kind, after {@code latencyMs}. */
        public static AttemptOutcome failed(FailureKind kind, long latencyMs) {
            return new AttemptOutcome(false, kind, latencyMs);
        }
    }

    /** Global circuit breaker thresholds — one policy shared by every route target. */
    public record BreakerConfig(int failThreshold, int windowSec, int cooldownSec) {

        public BreakerConfig {
            if (failThreshold <= 0) {
                throw new IllegalArgumentException("failThreshold must be > 0");
            }
            if (windowSec <= 0) {
                throw new IllegalArgumentException("windowSec must be > 0");
            }
            if (cooldownSec <= 0) {
                throw new IllegalArgumentException("cooldownSec must be > 0");
            }
        }
    }

    /** Mutable per-target breaker bookkeeping; {@link CircuitBreakerState} is its immutable snapshot. */
    private static final class Breaker {
        CircuitBreakerState.Phase phase = CircuitBreakerState.Phase.CLOSED;
        int consecutiveFailures;
        Instant windowStart;
        Instant openedAt;
        Instant nextRetryAt;

        synchronized CircuitBreakerState snapshot() {
            return new CircuitBreakerState(phase, consecutiveFailures, openedAt, nextRetryAt);
        }
    }

    /** The outcome of attempting one candidate, already mapped to a {@link RouteDecision.ReasonCode}. */
    private record Attempt(boolean succeeded, RouteTier tier, String targetId,
            RouteDecision.ReasonCode failureReason, long latencyMs) {
    }

    private final BreakerConfig breakerConfig;
    private final Clock clock;
    private final ConcurrentHashMap<String, Breaker> breakers = new ConcurrentHashMap<>();

    public RouteSelector(BreakerConfig breakerConfig) {
        this(breakerConfig, Clock.systemUTC());
    }

    public RouteSelector(BreakerConfig breakerConfig, Clock clock) {
        this.breakerConfig = Objects.requireNonNull(breakerConfig, "breakerConfig");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A read-only snapshot of a target's current breaker state ({@code CLOSED} if never seen). */
    public CircuitBreakerState breakerState(String targetId) {
        Breaker b = breakers.get(targetId);
        return b == null ? CircuitBreakerState.closed() : b.snapshot();
    }

    /**
     * Selects and attempts a route for {@code role}, delegating every attempt to {@code executor}.
     * The executor is supplied per call (not at construction) so a caller can close over per-call
     * context (prompt, system message, ...) while this instance's circuit breaker state — keyed by
     * target id — persists across calls regardless of which executor is passed. Empty
     * {@link AgentRoleRouteConfig#fallbackChain()} short-circuits to {@code NO_ROUTE_CONFIGURED}
     * without calling the executor.
     */
    public RouteDecision select(AgentRoleRouteConfig config, RouteExecutor executor) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(executor, "executor");
        List<FallbackRoute> chain = config.fallbackChain().stream()
                .sorted((a, b) -> Integer.compare(a.order(), b.order())).toList();
        if (chain.isEmpty()) {
            return new RouteDecision(config.role(), null, "",
                    RouteDecision.ReasonCode.NO_ROUTE_CONFIGURED, -1, false);
        }

        FallbackRoute primary = chain.get(0);
        Attempt primaryAttempt = tryCandidate(primary, executor);
        if (primaryAttempt.succeeded()) {
            return new RouteDecision(config.role(), primary.tier(), primary.targetId(),
                    RouteDecision.ReasonCode.PRIMARY_OK, primaryAttempt.latencyMs(), true);
        }
        RouteDecision.ReasonCode leftPrimaryBecause = primaryAttempt.failureReason();

        if (chain.size() == 1 || !config.failoverEnabled()) {
            RouteDecision.ReasonCode reason = (!config.failoverEnabled() && chain.size() > 1)
                    ? RouteDecision.ReasonCode.FAILOVER_DISABLED
                    : leftPrimaryBecause;
            return new RouteDecision(config.role(), primary.tier(), primary.targetId(), reason,
                    primaryAttempt.latencyMs(), false);
        }

        Attempt last = primaryAttempt;
        for (int i = 1; i < chain.size(); i++) {
            FallbackRoute candidate = chain.get(i);
            last = tryCandidate(candidate, executor);
            if (last.succeeded()) {
                return new RouteDecision(config.role(), candidate.tier(), candidate.targetId(),
                        leftPrimaryBecause, last.latencyMs(), true);
            }
        }
        return new RouteDecision(config.role(), last.tier(), last.targetId(),
                RouteDecision.ReasonCode.ALL_ROUTES_EXHAUSTED, last.latencyMs(), false);
    }

    // ---- candidate attempt + breaker transitions -------------------------------------------

    private Attempt tryCandidate(FallbackRoute candidate, RouteExecutor executor) {
        Breaker breaker = breakers.computeIfAbsent(candidate.targetId(), k -> new Breaker());

        synchronized (breaker) {
            if (breaker.phase == CircuitBreakerState.Phase.OPEN) {
                if (clock.instant().isBefore(breaker.nextRetryAt)) {
                    return new Attempt(false, candidate.tier(), candidate.targetId(),
                            RouteDecision.ReasonCode.CIRCUIT_OPEN, -1);
                }
                // Cooldown elapsed — allow exactly one probe through.
                breaker.phase = CircuitBreakerState.Phase.HALF_OPEN;
            }
        }

        AttemptOutcome outcome = executor.execute(candidate.targetId(), candidate.tier());

        synchronized (breaker) {
            if (outcome.success()) {
                closeBreaker(breaker);
                return new Attempt(true, candidate.tier(), candidate.targetId(), null, outcome.latencyMs());
            }
            recordFailure(breaker);
            return new Attempt(false, candidate.tier(), candidate.targetId(),
                    mapFailure(outcome.failureKind()), outcome.latencyMs());
        }
    }

    /** Must hold {@code breaker}'s monitor lock. */
    private void closeBreaker(Breaker breaker) {
        breaker.phase = CircuitBreakerState.Phase.CLOSED;
        breaker.consecutiveFailures = 0;
        breaker.windowStart = null;
        breaker.openedAt = null;
        breaker.nextRetryAt = null;
    }

    /** Must hold {@code breaker}'s monitor lock. */
    private void recordFailure(Breaker breaker) {
        Instant now = clock.instant();
        if (breaker.phase == CircuitBreakerState.Phase.HALF_OPEN) {
            // The recovery probe failed — trip straight back to OPEN regardless of the failure window.
            trip(breaker, now);
            return;
        }
        if (breaker.windowStart == null
                || now.isAfter(breaker.windowStart.plusSeconds(breakerConfig.windowSec()))) {
            breaker.windowStart = now;
            breaker.consecutiveFailures = 1;
        } else {
            breaker.consecutiveFailures++;
        }
        if (breaker.consecutiveFailures >= breakerConfig.failThreshold()) {
            trip(breaker, now);
        }
    }

    /** Must hold {@code breaker}'s monitor lock. */
    private void trip(Breaker breaker, Instant now) {
        breaker.phase = CircuitBreakerState.Phase.OPEN;
        breaker.openedAt = now;
        breaker.nextRetryAt = now.plusSeconds(breakerConfig.cooldownSec());
    }

    private static RouteDecision.ReasonCode mapFailure(FailureKind kind) {
        return switch (kind) {
            case TIMEOUT -> RouteDecision.ReasonCode.PRIMARY_TIMEOUT;
            case RATE_LIMITED -> RouteDecision.ReasonCode.PRIMARY_RATE_LIMITED;
            case SERVER_ERROR -> RouteDecision.ReasonCode.PRIMARY_SERVER_ERROR;
            case MALFORMED_RESPONSE -> RouteDecision.ReasonCode.PRIMARY_MALFORMED_RESPONSE;
            case OTHER -> RouteDecision.ReasonCode.PRIMARY_SERVER_ERROR;
        };
    }
}
