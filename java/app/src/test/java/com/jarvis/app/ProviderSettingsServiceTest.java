package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.memory.InMemoryStore;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ProviderSettingsServiceTest {

    @Test
    void testConnectionReportsAuthFailureFriendlyOn401() {
        ProviderSettingsService svc = new ProviderSettingsService(new InMemoryStore<>(),
                (baseUrl, apiKey) -> {
                    throw new IOException("model list returned 401");
                });
        svc.save("NVIDIA", "openai", "https://integrate.api.nvidia.com/v1", "bad-key", "m", false);
        ProviderSettingsService.TestResult r = svc.test("NVIDIA");
        assertFalse(r.ok());
        assertTrue(r.message().toLowerCase().contains("authentication failed"), r.message());
    }

    @Test
    void testConnectionSucceedsWhenModelsListReturns() {
        ProviderSettingsService svc = new ProviderSettingsService(new InMemoryStore<>(),
                (baseUrl, apiKey) -> java.util.List.of("a", "b", "c"));
        svc.save("NVIDIA", "openai", "https://x/v1", "good-key", "m", false);
        ProviderSettingsService.TestResult r = svc.test("NVIDIA");
        assertTrue(r.ok());
        assertTrue(r.message().contains("3"));
    }

    @Test
    void manyProvidersCoexistAndActiveSwitches() {
        ProviderSettingsService svc = new ProviderSettingsService(new InMemoryStore<>(),
                (b, k) -> java.util.List.of());
        svc.save("NVIDIA", "openai", "https://a/v1", "k1", "m1", true);
        svc.save("OpenRouter", "openai", "https://b/v1", "k2", "m2", false);
        svc.save("Groq", "openai", "https://c/v1", "k3", "m3", false);
        assertEquals(3, svc.list().size());
        assertEquals("NVIDIA", svc.active().orElseThrow().name());

        assertTrue(svc.activate("Groq"));
        assertEquals("Groq", svc.active().orElseThrow().name());
        assertFalse(svc.activate("ghost"));   // unknown name doesn't change active
        assertEquals("Groq", svc.active().orElseThrow().name());
    }

    @Test
    void removingTheActiveProviderClearsActive() {
        ProviderSettingsService svc = new ProviderSettingsService(new InMemoryStore<>(),
                (b, k) -> java.util.List.of());
        svc.save("NVIDIA", "openai", "https://a/v1", "k1", "m1", true);
        assertTrue(svc.remove("NVIDIA"));
        assertTrue(svc.active().isEmpty());
        assertTrue(svc.list().isEmpty());
    }

    @Test
    void roleAssignmentPersistsAndSurvivesResave() {
        ProviderSettingsService svc = new ProviderSettingsService(new InMemoryStore<>(),
                (b, k) -> java.util.List.of());
        svc.save("NVIDIA", "openai", "https://a/v1", "k1", "m1", true);
        assertTrue(svc.setRole("NVIDIA", "conductor"));
        assertEquals("conductor", svc.list().get(0).role());
        // An unknown role clears it; a model/key edit keeps the assigned role.
        assertTrue(svc.setRole("NVIDIA", "conductor"));
        svc.save("NVIDIA", "openai", "https://a/v1", "", "m2", true);   // edit model, no key
        assertEquals("conductor", svc.list().get(0).role());
        assertTrue(svc.setRole("NVIDIA", "bogus"));                     // invalid → cleared
        assertEquals("", svc.list().get(0).role());
        assertFalse(svc.setRole("ghost", "worker"));                    // unknown provider
    }

    @Test
    void blankKeyOnResaveKeepsTheStoredKey() {
        ProviderSettingsService svc = new ProviderSettingsService(new InMemoryStore<>(),
                (b, k) -> java.util.List.of());
        svc.save("NVIDIA", "openai", "https://a/v1", "secret", "m1", true);
        svc.save("NVIDIA", "openai", "https://a/v1", "", "m2", true);   // change model, no key
        assertEquals("secret", svc.active().orElseThrow().apiKey());
        assertEquals("m2", svc.active().orElseThrow().model());
    }
}
