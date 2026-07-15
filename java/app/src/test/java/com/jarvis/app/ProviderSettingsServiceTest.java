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

    @Test
    void withRoleReturnsExactlyTheProvidersInThatTier() {
        // withRole is the contract the orchestrator relies on to build each hierarchy tier.
        ProviderSettingsService svc = new ProviderSettingsService(new InMemoryStore<>(),
                (b, k) -> java.util.List.of());
        svc.save("Boss", "openai", "https://a/v1", "k", "m", false);
        svc.save("W1", "openai", "https://b/v1", "k", "m", false);
        svc.save("W2", "openai", "https://c/v1", "k", "m", false);
        svc.save("Idle", "openai", "https://d/v1", "k", "m", false);   // no role assigned
        svc.setRole("Boss", "conductor");
        svc.setRole("W1", "worker");
        svc.setRole("W2", "worker");

        assertEquals(1, svc.withRole("conductor").size());
        assertEquals("Boss", svc.withRole("conductor").get(0).name());
        java.util.Set<String> workers = new java.util.HashSet<>();
        svc.withRole("worker").forEach(a -> workers.add(a.name()));
        assertEquals(java.util.Set.of("W1", "W2"), workers);
        assertTrue(svc.withRole("orchestrator").isEmpty());            // tier with no members
        assertEquals("conductor", svc.roleOf("Boss"));
        assertEquals("", svc.roleOf("Idle"));
    }

    @Test
    void listNeverExposesTheApiKey() {
        // Security invariant: the UI-facing view carries only hasKey, never the secret itself.
        ProviderSettingsService svc = new ProviderSettingsService(new InMemoryStore<>(),
                (b, k) -> java.util.List.of());
        svc.save("NVIDIA", "openai", "https://a/v1", "super-secret-key", "m", true);
        svc.save("Local", "openai", "http://localhost:11434/v1", "", "m", false);
        ProviderSettingsService.ProviderView withKey = svc.list().stream()
                .filter(v -> v.name().equals("NVIDIA")).findFirst().orElseThrow();
        ProviderSettingsService.ProviderView noKey = svc.list().stream()
                .filter(v -> v.name().equals("Local")).findFirst().orElseThrow();
        assertTrue(withKey.hasKey());
        assertFalse(noKey.hasKey());
        assertFalse(withKey.toString().contains("super-secret-key"));  // not in any field
    }

    @Test
    void testAnthropicAcceptsAStoredKeyWithoutHittingTheNetwork() {
        // Native Anthropic has no /models probe — a stored key is reported as ready; a missing one isn't.
        ProviderSettingsService svc = new ProviderSettingsService(new InMemoryStore<>(),
                (b, k) -> { throw new IllegalStateException("network must not be touched"); });
        svc.save("Claude", "anthropic", "", "sk-ant-123", "claude-x", false);
        assertTrue(svc.test("Claude").ok());
        svc.save("ClaudeNoKey", "anthropic", "", "", "claude-x", false);
        ProviderSettingsService.TestResult r = svc.test("ClaudeNoKey");
        assertFalse(r.ok());
        assertTrue(r.message().contains("no API key"));
    }

    @Test
    void testLocalhostNeedsNoKeyButUnknownProviderFails() {
        // A localhost endpoint is allowed to probe with no key; an unconfigured name is rejected.
        ProviderSettingsService svc = new ProviderSettingsService(new InMemoryStore<>(),
                (b, k) -> java.util.List.of("llama3"));
        svc.save("Ollama", "openai", "http://localhost:11434/v1", "", "llama3", false);
        assertTrue(svc.test("Ollama").ok());
        assertFalse(svc.test("does-not-exist").ok());
        assertTrue(svc.test("does-not-exist").message().contains("no such provider"));
    }

    @Test
    void testMapsForbiddenAndNotFoundToFriendlyMessages() {
        ProviderSettingsService f403 = new ProviderSettingsService(new InMemoryStore<>(),
                (b, k) -> { throw new IOException("server said 403 Forbidden"); });
        f403.save("P", "openai", "https://a/v1", "k", "m", false);
        assertTrue(f403.test("P").message().contains("403"));
        assertTrue(f403.test("P").message().toLowerCase().contains("forbidden"));

        ProviderSettingsService f404 = new ProviderSettingsService(new InMemoryStore<>(),
                (b, k) -> { throw new IOException("got 404 Not Found"); });
        f404.save("P", "openai", "https://a/v1", "k", "m", false);
        assertTrue(f404.test("P").message().contains("404"));
    }
}
