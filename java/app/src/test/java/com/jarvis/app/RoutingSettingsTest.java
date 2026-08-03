package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.memory.InMemoryStore;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoutingSettingsTest {

    private static RoutingSettings settings(Map<String, String> env) {
        return new RoutingSettings(new ConnectorSettingsService(new InMemoryStore<>(), env::get));
    }

    @Test
    void defaultsAreSafeWhenNothingIsConfigured() {
        RoutingSettings.Snapshot s = settings(Map.of()).snapshot();
        assertFalse(s.openHumanEnabled()); // Tier 2 is off unless explicitly enabled
        assertTrue(s.failoverEnabled());
        assertEquals(RoutingSettings.DEFAULT_TIMEOUT_MS, s.timeoutMs());
        assertEquals(RoutingSettings.DEFAULT_MAX_RETRIES, s.maxRetries());
        assertEquals(RoutingSettings.DEFAULT_BREAKER_FAIL_THRESHOLD, s.breakerFailThreshold());
        assertEquals(RoutingSettings.DEFAULT_BREAKER_WINDOW_SEC, s.breakerWindowSec());
        assertEquals(RoutingSettings.DEFAULT_BREAKER_COOLDOWN_SEC, s.breakerCooldownSec());
    }

    @Test
    void explicitlySettingOpenHumanEnabledFalseStaysFalse() {
        RoutingSettings.Snapshot s = settings(Map.of("JARVIS_OPENHUMAN_ENABLED", "false")).snapshot();
        assertFalse(s.openHumanEnabled());
    }

    @Test
    void environmentVariablesAreHonoredWhenSet() {
        RoutingSettings.Snapshot s = settings(Map.of(
                "JARVIS_OPENHUMAN_ENABLED", "true",
                "JARVIS_ROUTING_FAILOVER_ENABLED", "false",
                "JARVIS_ROUTING_TIMEOUT_MS", "5000",
                "JARVIS_ROUTING_MAX_RETRIES", "3",
                "JARVIS_ROUTING_BREAKER_FAIL_THRESHOLD", "10",
                "JARVIS_ROUTING_BREAKER_WINDOW_SEC", "120",
                "JARVIS_ROUTING_BREAKER_COOLDOWN_SEC", "45")).snapshot();
        assertTrue(s.openHumanEnabled());
        assertFalse(s.failoverEnabled());
        assertEquals(5000, s.timeoutMs());
        assertEquals(3, s.maxRetries());
        assertEquals(10, s.breakerFailThreshold());
        assertEquals(120, s.breakerWindowSec());
        assertEquals(45, s.breakerCooldownSec());
    }

    @Test
    void invalidNumericValuesFallBackToDefaultsRatherThanThrowing() {
        RoutingSettings.Snapshot s = settings(Map.of(
                "JARVIS_ROUTING_TIMEOUT_MS", "not-a-number",
                "JARVIS_ROUTING_MAX_RETRIES", "-9",
                "JARVIS_ROUTING_BREAKER_FAIL_THRESHOLD", "0")).snapshot();
        assertEquals(RoutingSettings.DEFAULT_TIMEOUT_MS, s.timeoutMs());
        assertEquals(RoutingSettings.DEFAULT_MAX_RETRIES, s.maxRetries()); // negative retries is invalid, not 0
        assertEquals(RoutingSettings.DEFAULT_BREAKER_FAIL_THRESHOLD, s.breakerFailThreshold());
    }

    @Test
    void savedConnectorValueOverridesTheEnvironment() {
        ConnectorSettingsService connectors =
                new ConnectorSettingsService(new InMemoryStore<>(), Map.of("JARVIS_OPENHUMAN_ENABLED", "false")::get);
        connectors.set("openhuman.enabled", "true");
        assertTrue(new RoutingSettings(connectors).snapshot().openHumanEnabled());
    }
}
