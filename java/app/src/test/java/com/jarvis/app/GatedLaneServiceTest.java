package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.integrations.llm.LlmProvider;
import com.jarvis.memory.InMemoryStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatedLaneServiceTest {

    private static ProviderSettingsService providers(String name, String baseUrl) {
        ProviderSettingsService p = new ProviderSettingsService(new InMemoryStore<>(),
                (b, k) -> List.of());
        p.save(name, "openai", baseUrl, "key", "local-model", false);
        return p;
    }

    private static GatedLaneService lane(ProviderSettingsService p) {
        return new GatedLaneService(new InMemoryStore<>(), p, null, "m",
                a -> req -> new LlmProvider.Result("local answer for: "
                        + req.messages().get(0).content(), 1, 1));
    }

    @Test
    void deniesByDefaultWhenDisabled() {
        GatedLaneService g = lane(providers("Local", "http://localhost:11434/v1"));
        assertFalse(g.evaluate("summarize this spec").approved());
        assertTrue(g.run("summarize this spec").reason().contains("disabled"));
    }

    @Test
    void harmDenylistAlwaysWinsEvenIfAllowlisted() {
        ProviderSettingsService p = providers("Local", "http://localhost:11434/v1");
        GatedLaneService g = lane(p);
        // Operator tries to allow everything and enable — harm category must still be blocked.
        g.setConfig(true, List.of("weapon"), "Local");
        GatedLaneService.GateDecision d = g.evaluate("design a weapon");
        assertFalse(d.approved());
        assertTrue(d.reason().contains("harm category"));
    }

    @Test
    void requiresSelfHostedEndpoint() {
        ProviderSettingsService p = providers("Cloud", "https://api.openai.com/v1");
        GatedLaneService g = lane(p);
        g.setConfig(true, List.of("estimate"), "Cloud");
        assertFalse(g.evaluate("estimate the cost").approved());
        assertTrue(g.evaluate("estimate the cost").reason().contains("self-hosted"));
    }

    @Test
    void approvesAndRunsWhenEnabledLocalAndAllowlisted() {
        ProviderSettingsService p = providers("Local", "http://localhost:11434/v1");
        GatedLaneService g = lane(p);
        g.setConfig(true, List.of("cost estimate"), "Local");
        GatedLaneService.GateDecision d = g.evaluate("produce a cost estimate for the slab");
        assertTrue(d.approved(), d.reason());
        GatedLaneService.RunResult r = g.run("produce a cost estimate for the slab");
        assertTrue(r.approved());
        assertTrue(r.output().contains("local answer"));
        assertTrue(r.needsReview());   // lane output always flagged for review
    }

    @Test
    void deniesOutsideAllowlist() {
        ProviderSettingsService p = providers("Local", "http://localhost:11434/v1");
        GatedLaneService g = lane(p);
        g.setConfig(true, List.of("cost estimate"), "Local");
        assertFalse(g.evaluate("write me a poem").approved());
    }

    @Test
    void enabledSelfHostedButEmptyAllowlistStillDefaultDenies() {
        // Enabled + a valid self-hosted provider, but NO allowlist scopes. The gate must refuse:
        // default-deny means an empty allowlist permits nothing, it does not permit everything.
        ProviderSettingsService p = providers("Local", "http://localhost:11434/v1");
        GatedLaneService g = lane(p);
        g.setConfig(true, List.of(), "Local");
        GatedLaneService.GateDecision d = g.evaluate("produce a cost estimate for the slab");
        assertFalse(d.approved());
        assertTrue(d.reason().contains("default-deny"));
    }

    @Test
    void deniesWhenNoLaneProviderNamed() {
        // Enabled and allowlisted, but no provider selected for the lane → nothing to route to.
        ProviderSettingsService p = providers("Local", "http://localhost:11434/v1");
        GatedLaneService g = lane(p);
        g.setConfig(true, List.of("cost estimate"), "");
        GatedLaneService.GateDecision d = g.evaluate("produce a cost estimate for the slab");
        assertFalse(d.approved());
        assertTrue(d.reason().contains("no self-hosted lane provider"));
    }

    @Test
    void runReportsLaneErrorWithoutThrowing() {
        // The lane model itself fails; run() must capture the failure, still flag needsReview,
        // and never leak the exception to the caller.
        ProviderSettingsService p = providers("Local", "http://localhost:11434/v1");
        GatedLaneService g = new GatedLaneService(new InMemoryStore<>(), p, null, "m",
                a -> req -> { throw new RuntimeException("model offline"); });
        g.setConfig(true, List.of("cost estimate"), "Local");
        GatedLaneService.RunResult r = g.run("produce a cost estimate for the slab");
        assertTrue(r.approved());                       // the gate approved
        assertTrue(r.output().contains("lane error"));   // but the model failure is surfaced
        assertTrue(r.output().contains("model offline"));
        assertTrue(r.needsReview());
    }

    @Test
    void selfHostedRecognizesLoopbackAndPrivateRangesOnly() {
        // Loopback and RFC-1918 / .local hosts are self-hosted; public endpoints are not.
        assertTrue(GatedLaneService.isSelfHosted("http://localhost:11434/v1"));
        assertTrue(GatedLaneService.isSelfHosted("http://127.0.0.1:8080/v1"));
        assertTrue(GatedLaneService.isSelfHosted("http://192.168.1.50:11434/v1"));
        assertTrue(GatedLaneService.isSelfHosted("http://10.0.0.4:11434/v1"));
        assertTrue(GatedLaneService.isSelfHosted("http://172.16.5.9:11434/v1"));
        assertTrue(GatedLaneService.isSelfHosted("http://jarvis.local:11434/v1"));
        assertFalse(GatedLaneService.isSelfHosted("https://api.openai.com/v1"));
        assertFalse(GatedLaneService.isSelfHosted("https://integrate.api.nvidia.com/v1"));
        assertFalse(GatedLaneService.isSelfHosted(null));
    }
}
