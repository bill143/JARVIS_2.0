package com.jarvis.integrations.openhuman.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.integrations.openhuman.routing.RouteSelector.AttemptOutcome;
import com.jarvis.integrations.openhuman.routing.RouteSelector.BreakerConfig;
import com.jarvis.integrations.openhuman.routing.RouteSelector.FailureKind;
import com.jarvis.integrations.openhuman.routing.RouteSelector.RouteExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RouteSelectorTest {

    /** A clock a test can advance deterministically — no {@code Thread.sleep} timing windows. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final BreakerConfig BREAKER = new RouteSelector.BreakerConfig(3, 60, 30);

    private static AgentRoleRouteConfig singleRoute(String targetId, boolean failoverEnabled) {
        return new AgentRoleRouteConfig("worker", failoverEnabled,
                List.of(new FallbackRoute(0, targetId, RouteTier.TIER1_PRIMARY)), 5000, 1);
    }

    private static AgentRoleRouteConfig primaryPlusOpenHumanFallback(boolean failoverEnabled) {
        return new AgentRoleRouteConfig("worker", failoverEnabled, List.of(
                new FallbackRoute(0, "primary-provider", RouteTier.TIER1_PRIMARY),
                new FallbackRoute(1, "openhuman", RouteTier.TIER2_OPENHUMAN)), 5000, 1);
    }

    // ---- 1. Primary routing under nominal conditions ----------------------------------------

    @Test
    void primarySucceedsUnderNominalConditions() {
        RouteSelector selector = new RouteSelector(BREAKER);
        RouteDecision d = selector.select(singleRoute("primary-provider", true),
                (targetId, tier) -> AttemptOutcome.ok(42));

        assertTrue(d.success());
        assertEquals(RouteTier.TIER1_PRIMARY, d.selectedTier());
        assertEquals("primary-provider", d.selectedTarget());
        assertEquals(RouteDecision.ReasonCode.PRIMARY_OK, d.reasonCode());
        assertEquals(42, d.latencyMs());
    }

    @Test
    void emptyFallbackChainIsNoRouteConfiguredAndNeverCallsTheExecutor() {
        AtomicInteger calls = new AtomicInteger();
        RouteSelector selector = new RouteSelector(BREAKER);
        RouteExecutor executor = (targetId, tier) -> {
            calls.incrementAndGet();
            return AttemptOutcome.ok(1);
        };

        RouteDecision d = selector.select(
                new AgentRoleRouteConfig("worker", true, List.of(), 5000, 1), executor);

        assertFalse(d.success());
        assertNull(d.selectedTier());
        assertEquals(RouteDecision.ReasonCode.NO_ROUTE_CONFIGURED, d.reasonCode());
        assertEquals(-1, d.latencyMs());
        assertEquals(0, calls.get());
    }

    @Test
    void theBreakerPersistsAcrossCallsEvenWhenTheExecutorInstanceChangesEachTime() {
        // Different lambda object every call (as production code does — closing over per-call
        // prompt/system) — the SAME RouteSelector must still remember the target's failure count.
        RouteSelector selector = new RouteSelector(BREAKER);
        AgentRoleRouteConfig config = singleRoute("flaky", true);

        selector.select(config, (targetId, tier) -> AttemptOutcome.failed(FailureKind.SERVER_ERROR, 1));
        selector.select(config, (targetId, tier) -> AttemptOutcome.failed(FailureKind.SERVER_ERROR, 1));
        selector.select(config, (targetId, tier) -> AttemptOutcome.failed(FailureKind.SERVER_ERROR, 1));

        assertEquals(CircuitBreakerState.Phase.OPEN, selector.breakerState("flaky").phase());
    }

    // ---- 2. Fallback tripping on 429 / timeout -----------------------------------------------

    @Test
    void failsOverToOpenHumanWhenPrimaryTimesOut() {
        RouteSelector selector = new RouteSelector(BREAKER);
        RouteDecision d = selector.select(primaryPlusOpenHumanFallback(true), (targetId, tier) ->
                "primary-provider".equals(targetId)
                        ? AttemptOutcome.failed(FailureKind.TIMEOUT, 5000)
                        : AttemptOutcome.ok(200));

        assertTrue(d.success());
        assertEquals(RouteTier.TIER2_OPENHUMAN, d.selectedTier());
        assertEquals("openhuman", d.selectedTarget());
        assertEquals(RouteDecision.ReasonCode.PRIMARY_TIMEOUT, d.reasonCode());
        assertEquals(200, d.latencyMs());
    }

    @Test
    void failsOverToOpenHumanWhenPrimaryIsRateLimited() {
        RouteSelector selector = new RouteSelector(BREAKER);
        RouteDecision d = selector.select(primaryPlusOpenHumanFallback(true), (targetId, tier) ->
                "primary-provider".equals(targetId)
                        ? AttemptOutcome.failed(FailureKind.RATE_LIMITED, 10)
                        : AttemptOutcome.ok(150));

        assertTrue(d.success());
        assertEquals(RouteTier.TIER2_OPENHUMAN, d.selectedTier());
        assertEquals(RouteDecision.ReasonCode.PRIMARY_RATE_LIMITED, d.reasonCode());
    }

    @Test
    void noFallbackIsAttemptedWhenFailoverIsDisabled() {
        AtomicInteger openHumanCalls = new AtomicInteger();
        RouteSelector selector = new RouteSelector(BREAKER);
        RouteExecutor executor = (targetId, tier) -> {
            if ("openhuman".equals(targetId)) {
                openHumanCalls.incrementAndGet();
            }
            return "primary-provider".equals(targetId)
                    ? AttemptOutcome.failed(FailureKind.SERVER_ERROR, 5)
                    : AttemptOutcome.ok(5);
        };

        RouteDecision d = selector.select(primaryPlusOpenHumanFallback(false), executor);

        assertFalse(d.success());
        assertEquals(RouteTier.TIER1_PRIMARY, d.selectedTier());
        assertEquals(RouteDecision.ReasonCode.FAILOVER_DISABLED, d.reasonCode());
        assertEquals(0, openHumanCalls.get());
    }

    @Test
    void allRoutesExhaustedWhenEveryCandidateFails() {
        RouteSelector selector = new RouteSelector(BREAKER);
        RouteDecision d = selector.select(primaryPlusOpenHumanFallback(true),
                (targetId, tier) -> AttemptOutcome.failed(FailureKind.SERVER_ERROR, 3));

        assertFalse(d.success());
        assertEquals("openhuman", d.selectedTarget()); // the last candidate attempted
        assertEquals(RouteDecision.ReasonCode.ALL_ROUTES_EXHAUSTED, d.reasonCode());
    }

    @Test
    void malformedResponseIsMappedToItsOwnReasonCode() {
        RouteSelector selector = new RouteSelector(BREAKER);
        RouteDecision d = selector.select(singleRoute("primary-provider", true),
                (targetId, tier) -> AttemptOutcome.failed(FailureKind.MALFORMED_RESPONSE, 5));

        assertEquals(RouteDecision.ReasonCode.PRIMARY_MALFORMED_RESPONSE, d.reasonCode());
    }

    // ---- 3. Circuit breaker: Closed -> Open -> Half-Open -> Closed --------------------------

    @Test
    void breakerTripsOpenAfterThresholdFailuresWithinWindowAndThenFailsFast() {
        AtomicInteger calls = new AtomicInteger();
        RouteSelector selector = new RouteSelector(BREAKER);
        RouteExecutor executor = (targetId, tier) -> {
            calls.incrementAndGet();
            return AttemptOutcome.failed(FailureKind.SERVER_ERROR, 5);
        };
        AgentRoleRouteConfig config = singleRoute("flaky", true);

        selector.select(config, executor);
        selector.select(config, executor);
        RouteDecision third = selector.select(config, executor); // 3rd failure crosses threshold=3

        assertEquals(CircuitBreakerState.Phase.OPEN, selector.breakerState("flaky").phase());
        assertEquals(3, selector.breakerState("flaky").consecutiveFailures());
        assertEquals(RouteDecision.ReasonCode.PRIMARY_SERVER_ERROR, third.reasonCode());
        assertEquals(3, calls.get());

        RouteDecision fourth = selector.select(config, executor); // OPEN, cooldown not elapsed
        assertFalse(fourth.success());
        assertEquals(RouteDecision.ReasonCode.CIRCUIT_OPEN, fourth.reasonCode());
        assertEquals(-1, fourth.latencyMs());
        assertEquals(3, calls.get()); // the open breaker short-circuited — no network/executor call
    }

    @Test
    void breakerAllowsAHalfOpenProbeAfterCooldownAndClosesOnSuccess() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AtomicInteger calls = new AtomicInteger();
        Map<Integer, AttemptOutcome> scripted = Map.of(
                0, AttemptOutcome.failed(FailureKind.SERVER_ERROR, 5),
                1, AttemptOutcome.failed(FailureKind.SERVER_ERROR, 5),
                2, AttemptOutcome.failed(FailureKind.SERVER_ERROR, 5),
                3, AttemptOutcome.ok(20)); // the post-cooldown recovery probe succeeds
        RouteSelector selector = new RouteSelector(BREAKER, clock);
        RouteExecutor executor = (targetId, tier) -> scripted.get(calls.getAndIncrement());
        AgentRoleRouteConfig config = singleRoute("flaky", true);

        selector.select(config, executor);
        selector.select(config, executor);
        selector.select(config, executor); // trips OPEN (3 failures within the 60s window)
        assertEquals(CircuitBreakerState.Phase.OPEN, selector.breakerState("flaky").phase());

        clock.advance(Duration.ofSeconds(31)); // past the 30s cooldown
        RouteDecision probe = selector.select(config, executor);

        assertTrue(probe.success());
        assertEquals(RouteDecision.ReasonCode.PRIMARY_OK, probe.reasonCode());
        assertEquals(CircuitBreakerState.Phase.CLOSED, selector.breakerState("flaky").phase());
        assertEquals(0, selector.breakerState("flaky").consecutiveFailures());
        assertEquals(4, calls.get()); // the probe DID reach the executor (unlike a still-open breaker)
    }

    @Test
    void breakerTripsBackToOpenWhenTheHalfOpenProbeAlsoFails() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        RouteSelector selector = new RouteSelector(BREAKER, clock);
        RouteExecutor executor = (targetId, tier) -> AttemptOutcome.failed(FailureKind.SERVER_ERROR, 5);
        AgentRoleRouteConfig config = singleRoute("flaky", true);

        selector.select(config, executor);
        selector.select(config, executor);
        selector.select(config, executor); // OPEN
        Instant firstOpenedAt = selector.breakerState("flaky").openedAt();

        clock.advance(Duration.ofSeconds(31));
        RouteDecision probe = selector.select(config, executor); // half-open probe fails

        assertFalse(probe.success());
        assertEquals(CircuitBreakerState.Phase.OPEN, selector.breakerState("flaky").phase());
        assertTrue(selector.breakerState("flaky").openedAt().isAfter(firstOpenedAt));

        // Still open immediately after re-tripping — no second probe allowed yet.
        RouteDecision immediatelyAfter = selector.select(config, executor);
        assertEquals(RouteDecision.ReasonCode.CIRCUIT_OPEN, immediatelyAfter.reasonCode());
    }

    @Test
    void failuresOutsideTheWindowDoNotAccumulateTowardTheThreshold() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        RouteSelector selector = new RouteSelector(new RouteSelector.BreakerConfig(3, 10, 30), clock);
        RouteExecutor executor = (targetId, tier) -> AttemptOutcome.failed(FailureKind.SERVER_ERROR, 5);
        AgentRoleRouteConfig config = singleRoute("flaky2", true);

        selector.select(config, executor); // failure #1 at t=0, window starts
        clock.advance(Duration.ofSeconds(20)); // window (10s) has elapsed
        selector.select(config, executor); // window resets: failure #1 again at t=20
        assertEquals(CircuitBreakerState.Phase.CLOSED, selector.breakerState("flaky2").phase());
        assertEquals(1, selector.breakerState("flaky2").consecutiveFailures());

        clock.advance(Duration.ofSeconds(1));
        selector.select(config, executor); // failure #2 at t=21, still within the new window
        clock.advance(Duration.ofSeconds(1));
        RouteDecision third = selector.select(config, executor); // failure #3 at t=22 — trips

        assertFalse(third.success());
        assertEquals(CircuitBreakerState.Phase.OPEN, selector.breakerState("flaky2").phase());
        assertEquals(3, selector.breakerState("flaky2").consecutiveFailures());
    }

    @Test
    void breakerStateForAnUnseenTargetIsClosed() {
        RouteSelector selector = new RouteSelector(BREAKER);
        assertEquals(CircuitBreakerState.closed(), selector.breakerState("never-called"));
    }
}
