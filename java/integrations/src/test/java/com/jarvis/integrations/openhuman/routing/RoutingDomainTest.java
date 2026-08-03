package com.jarvis.integrations.openhuman.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RoutingDomainTest {

    @Test
    void agentRoleRouteConfigNormalizesRoleAndDefaultsTimeout() {
        AgentRoleRouteConfig cfg = new AgentRoleRouteConfig("  Conductor  ", true, null, 0, -5);
        assertEquals("conductor", cfg.role());
        assertEquals(List.of(), cfg.fallbackChain());
        assertEquals(AgentRoleRouteConfig.DEFAULT_TIMEOUT_MS, cfg.timeoutMs());
        assertEquals(0, cfg.maxRetries()); // negative retries clamp to 0
    }

    @Test
    void agentRoleRouteConfigFallbackChainIsImmutable() {
        var mutable = new java.util.ArrayList<FallbackRoute>();
        mutable.add(new FallbackRoute(0, "openhuman", RouteTier.TIER2_OPENHUMAN));
        AgentRoleRouteConfig cfg = new AgentRoleRouteConfig("worker", true, mutable, 5000, 1);
        mutable.add(new FallbackRoute(1, "backup", RouteTier.TIER1_PRIMARY));
        assertEquals(1, cfg.fallbackChain().size()); // unaffected by post-construction mutation
        assertThrows(UnsupportedOperationException.class,
                () -> cfg.fallbackChain().add(new FallbackRoute(2, "x", RouteTier.TIER2_OPENHUMAN)));
    }

    @Test
    void fallbackRouteRejectsNegativeOrderAndBlankTarget() {
        assertThrows(IllegalArgumentException.class,
                () -> new FallbackRoute(-1, "openhuman", RouteTier.TIER2_OPENHUMAN));
        assertThrows(IllegalArgumentException.class,
                () -> new FallbackRoute(0, "  ", RouteTier.TIER2_OPENHUMAN));
    }

    @Test
    void fallbackRouteDefaultsTierToOpenHumanWhenUnspecified() {
        FallbackRoute r = new FallbackRoute(0, "openhuman", null);
        assertEquals(RouteTier.TIER2_OPENHUMAN, r.tier());
    }

    @Test
    void circuitBreakerClosedFactoryStartsAtZeroFailures() {
        CircuitBreakerState s = CircuitBreakerState.closed();
        assertEquals(CircuitBreakerState.Phase.CLOSED, s.phase());
        assertEquals(0, s.consecutiveFailures());
        assertNull(s.openedAt());
        assertNull(s.nextRetryAt());
    }

    @Test
    void circuitBreakerRejectsNegativeFailureCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new CircuitBreakerState(CircuitBreakerState.Phase.CLOSED, -1, null, null));
    }

    @Test
    void routeDecisionNormalizesRoleAndDefaultsReasonCode() {
        RouteDecision d = new RouteDecision("  Worker  ", RouteTier.TIER2_OPENHUMAN, " openhuman ", null,
                120, true);
        assertEquals("worker", d.role());
        assertEquals("openhuman", d.selectedTarget());
        assertEquals(RouteDecision.ReasonCode.NO_ROUTE_CONFIGURED, d.reasonCode());
    }

    @Test
    void routeDecisionCapturesAFailoverOutcome() {
        RouteDecision d = new RouteDecision("worker", RouteTier.TIER2_OPENHUMAN, "openhuman",
                RouteDecision.ReasonCode.PRIMARY_TIMEOUT, 30_000, true);
        assertTrue(d.success());
        assertEquals(RouteTier.TIER2_OPENHUMAN, d.selectedTier());
        assertFalse(d.reasonCode() == RouteDecision.ReasonCode.PRIMARY_OK);
    }
}
