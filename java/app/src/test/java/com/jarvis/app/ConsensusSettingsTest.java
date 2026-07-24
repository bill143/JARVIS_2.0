package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.discussion.ConsensusMode;
import com.jarvis.discussion.ConsensusPolicy;
import com.jarvis.memory.InMemoryStore;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsensusSettingsTest {

    private static ConsensusSettings settings(Map<String, String> env) {
        return new ConsensusSettings(new ConnectorSettingsService(new InMemoryStore<>(), env::get));
    }

    @Test
    void defaultsAreInertWhenNothingIsConfigured() {
        ConsensusSettings.Snapshot s = settings(Map.of()).snapshot();
        assertFalse(s.enabled());
        assertEquals(ConsensusMode.OFF, s.mode());
        assertEquals(3, s.maxRounds());
        assertTrue(s.requireRationale());
        assertEquals(5000L, s.timeoutMs());
        assertEquals(ConsensusPolicy.off(), s.policy()); // disabled snapshot always yields OFF
    }

    @Test
    void environmentVariablesAreHonoredWhenSet() {
        ConsensusSettings.Snapshot s = settings(Map.of(
                "JARVIS_CONSENSUS_ENABLED", "true",
                "JARVIS_CONSENSUS_MODE", "UNANIMOUS",
                "JARVIS_CONSENSUS_MAX_ROUNDS", "5",
                "JARVIS_CONSENSUS_REQUIRE_RATIONALE", "false",
                "JARVIS_CONSENSUS_TIMEOUT_MS", "9000")).snapshot();
        assertTrue(s.enabled());
        assertEquals(ConsensusMode.UNANIMOUS, s.mode());
        assertEquals(5, s.maxRounds());
        assertFalse(s.requireRationale());
        assertEquals(9000L, s.timeoutMs());
    }

    @Test
    void invalidModeFallsBackToOffRatherThanThrowing() {
        ConsensusSettings.Snapshot s = settings(Map.of("JARVIS_CONSENSUS_MODE", "not-a-mode")).snapshot();
        assertEquals(ConsensusMode.OFF, s.mode());
    }

    @Test
    void invalidNumericValuesFallBackToDefaults() {
        ConsensusSettings.Snapshot s = settings(Map.of(
                "JARVIS_CONSENSUS_MAX_ROUNDS", "not-a-number",
                "JARVIS_CONSENSUS_TIMEOUT_MS", "-5")).snapshot();
        assertEquals(ConsensusSettings.DEFAULT_MAX_ROUNDS, s.maxRounds());
        assertEquals(ConsensusSettings.DEFAULT_TIMEOUT_MS, s.timeoutMs());
    }

    @Test
    void savedConnectorValueOverridesTheEnvironment() {
        ConnectorSettingsService connectors = new ConnectorSettingsService(new InMemoryStore<>(),
                Map.of("JARVIS_CONSENSUS_ENABLED", "false")::get);
        connectors.set("consensus.enabled", "true");
        assertTrue(new ConsensusSettings(connectors).snapshot().enabled());
    }

    // ---- effectivePolicy(): the default-deny choke point --------------------------------------

    @Test
    void requestOverrideIsIgnoredWhenGloballyDisabled() {
        ConsensusSettings settings = settings(Map.of()); // globally disabled (the default)
        ConsensusPolicy attemptedOverride =
                new ConsensusPolicy(ConsensusMode.UNANIMOUS, 5, true, 1000L);
        ConsensusPolicy effective = settings.effectivePolicy(attemptedOverride);
        assertEquals(ConsensusPolicy.off(), effective);
    }

    @Test
    void requestOverrideAppliesWhenGloballyEnabled() {
        ConsensusSettings settings = settings(Map.of("JARVIS_CONSENSUS_ENABLED", "true"));
        ConsensusPolicy override = new ConsensusPolicy(ConsensusMode.UNANIMOUS, 2, false, 2000L);
        assertEquals(override, settings.effectivePolicy(override));
    }

    @Test
    void nullOverrideFallsBackToTheGlobalSnapshotWhenEnabled() {
        ConsensusSettings settings = settings(Map.of(
                "JARVIS_CONSENSUS_ENABLED", "true", "JARVIS_CONSENSUS_MODE", "UNANIMOUS"));
        ConsensusPolicy effective = settings.effectivePolicy(null);
        assertEquals(ConsensusMode.UNANIMOUS, effective.mode());
        assertEquals(ConsensusSettings.DEFAULT_MAX_ROUNDS, effective.maxRounds());
    }

    @Test
    void nullOverrideStaysOffWhenGloballyDisabled() {
        ConsensusSettings settings = settings(Map.of());
        assertEquals(ConsensusPolicy.off(), settings.effectivePolicy(null));
    }
}
