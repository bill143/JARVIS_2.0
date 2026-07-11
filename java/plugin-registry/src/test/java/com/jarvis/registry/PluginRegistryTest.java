package com.jarvis.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jarvis.tools.RiskTier;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginRegistryTest {

    private static PluginRegistry registry() {
        return new PluginRegistry(List.of(
                new ToolManifest("clock", "time", RiskTier.READ_ONLY, List.of()),
                new ToolManifest("power", "power off", RiskTier.DESTRUCTIVE, List.of())));
    }

    @Test
    void riskTierComesFromTheManifestOrUnknown() {
        PluginRegistry r = registry();
        assertEquals(RiskTier.READ_ONLY, r.riskTier("clock"));
        assertEquals(RiskTier.DESTRUCTIVE, r.riskTier("power"));
        assertEquals(RiskTier.UNKNOWN, r.riskTier("never_declared"));
    }

    @Test
    void healthTransitionsThroughDegradedToCircuitOpenAndResetsOnSuccess() {
        PluginRegistry r = new PluginRegistry(registry().allStats().stream()
                .map(s -> new ToolManifest(s.name(), "", s.riskTier(), List.of())).toList(), 3);

        assertEquals(ToolHealth.OPERATIONAL, r.health("power"));   // never called
        r.recordFailure("power", "boom");
        assertEquals(ToolHealth.DEGRADED, r.health("power"));      // 1 consecutive
        r.recordFailure("power", "boom");
        r.recordFailure("power", "boom");
        assertEquals(ToolHealth.CIRCUIT_OPEN, r.health("power"));  // 3 consecutive >= threshold
        r.recordSuccess("power");
        assertEquals(ToolHealth.OPERATIONAL, r.health("power"));   // streak cleared
    }

    @Test
    void statsCountTotalsAndFailuresAndSurfaceLastError() {
        PluginRegistry r = registry();
        r.recordSuccess("clock");
        r.recordFailure("clock", "no clock");
        r.recordSuccess("clock");
        ToolStats s = r.stats("clock");
        assertEquals(3, s.totalCalls());
        assertEquals(1, s.failures());
        assertEquals(0, s.consecutiveFailures());   // last call succeeded
        assertEquals(RiskTier.READ_ONLY, s.riskTier());
    }

    @Test
    void allStatsIncludesManifestedToolsEvenIfNeverCalled() {
        List<ToolStats> stats = registry().allStats();
        assertEquals(2, stats.size());                       // clock + power, sorted
        assertEquals("clock", stats.get(0).name());
        assertEquals("power", stats.get(1).name());
    }

    @Test
    void rejectsNonPositiveCircuitThreshold() {
        assertThrows(IllegalArgumentException.class, () -> new PluginRegistry(List.of(), 0));
    }
}
