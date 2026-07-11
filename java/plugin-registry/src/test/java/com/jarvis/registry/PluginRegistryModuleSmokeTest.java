package com.jarvis.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jarvis.tools.RiskTier;
import org.junit.jupiter.api.Test;

class PluginRegistryModuleSmokeTest {

    @Test
    void manifestFlowsFromJsonToRiskTierLookup() {
        PluginRegistry registry = new PluginRegistry(ManifestLoader.parseArray(
                "[{\"name\":\"email_trash\",\"riskTier\":\"DESTRUCTIVE\"}]"));
        assertEquals(RiskTier.DESTRUCTIVE, registry.riskTier("email_trash"));
    }
}
