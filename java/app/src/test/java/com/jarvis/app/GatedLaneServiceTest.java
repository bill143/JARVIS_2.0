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
}
