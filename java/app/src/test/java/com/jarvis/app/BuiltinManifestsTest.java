package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.registry.PluginRegistry;
import com.jarvis.tools.RiskTier;
import org.junit.jupiter.api.Test;

/** Guards the bundled builtin.json: it must parse and pin the safety-critical tools' risk tiers. */
class BuiltinManifestsTest {

    private final PluginRegistry registry = AppWiring.pluginRegistry();

    @Test
    void destructiveToolsAreTieredDestructive() {
        assertEquals(RiskTier.DESTRUCTIVE, registry.riskTier("power"));
        assertEquals(RiskTier.DESTRUCTIVE, registry.riskTier("email_send"));
        assertEquals(RiskTier.DESTRUCTIVE, registry.riskTier("email_trash"));
        assertEquals(RiskTier.DESTRUCTIVE, registry.riskTier("email_unsubscribe"));
    }

    @Test
    void readOnlyToolsAreTieredReadOnly() {
        assertEquals(RiskTier.READ_ONLY, registry.riskTier("clock"));
        assertEquals(RiskTier.READ_ONLY, registry.riskTier("email_list"));
        assertEquals(RiskTier.READ_ONLY, registry.riskTier("screen_look"));
    }

    @Test
    void mutatingToolsAreTieredMutating() {
        assertEquals(RiskTier.MUTATING, registry.riskTier("volume"));
        assertEquals(RiskTier.MUTATING, registry.riskTier("calendar_create"));
        assertEquals(RiskTier.MUTATING, registry.riskTier("remember"));
    }

    @Test
    void noBuiltinToolIsLeftUnclassified() {
        // Every manifest we ship must declare a real tier (not UNKNOWN).
        long unclassified = registry.allStats().stream()
                .filter(s -> s.riskTier() == RiskTier.UNKNOWN).count();
        assertEquals(0, unclassified);
        assertTrue(registry.allStats().size() >= 25);   // all built-in tools covered
    }
}
