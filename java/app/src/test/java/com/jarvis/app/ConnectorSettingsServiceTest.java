package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.memory.InMemoryStore;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ConnectorSettingsServiceTest {

    private static ConnectorSettingsService svc(Map<String, String> env) {
        return new ConnectorSettingsService(new InMemoryStore<>(), env::get);
    }

    @Test
    void savedValueWinsOverEnvironmentAndClearingRevertsToEnv() {
        ConnectorSettingsService s = svc(Map.of("SAMGOV_API_KEY", "from-env"));
        // With nothing saved, the environment is the fallback.
        assertEquals("from-env", s.resolve("samgov.apiKey", "SAMGOV_API_KEY"));
        // A saved value overrides the environment.
        s.set("samgov.apiKey", "from-ui");
        assertEquals("from-ui", s.resolve("samgov.apiKey", "SAMGOV_API_KEY"));
        // Clearing reverts to the environment fallback.
        s.set("samgov.apiKey", "");
        assertEquals("from-env", s.resolve("samgov.apiKey", "SAMGOV_API_KEY"));
    }

    @Test
    void resolvesNullWhenNeitherStoreNorEnvHasValue() {
        ConnectorSettingsService s = svc(Map.of());
        assertNull(s.resolve("github.token", "JARVIS_GITHUB_TOKEN"));
        assertFalse(s.has("github.token", "JARVIS_GITHUB_TOKEN"));
        s.set("github.token", "ghp_x");
        assertTrue(s.has("github.token", "JARVIS_GITHUB_TOKEN"));
    }

    @Test
    void supplierResolvesLiveSoSavingTakesEffectWithoutRebuilding() {
        ConnectorSettingsService s = svc(Map.of());
        Supplier<String> token = s.supplier("github.token", "JARVIS_GITHUB_TOKEN");
        assertNull(token.get());               // dormant
        s.set("github.token", "ghp_live");
        assertEquals("ghp_live", token.get()); // same supplier now resolves the saved value — no restart
    }

    @Test
    void viewMasksSecretsButReturnsPlainFieldsAndReportsSet() {
        ConnectorSettingsService s = svc(Map.of());
        s.set("openhuman.url", "http://127.0.0.1:8765");
        s.set("openhuman.token", "secret-token");
        List<ConnectorSettingsService.Field> fields =
                s.view(ConnectorSettingsService.CONNECTORS.get("openhuman"));

        ConnectorSettingsService.Field url = fields.stream()
                .filter(f -> f.key().equals("openhuman.url")).findFirst().orElseThrow();
        ConnectorSettingsService.Field tok = fields.stream()
                .filter(f -> f.key().equals("openhuman.token")).findFirst().orElseThrow();

        assertFalse(url.secret());
        assertTrue(url.set());
        assertEquals("http://127.0.0.1:8765", url.value());   // plain field prefilled

        assertTrue(tok.secret());
        assertTrue(tok.set());                                // reports a value is stored
        assertEquals("", tok.value());                        // but never returns the secret
    }

    @Test
    void catalogCoversAllSevenConnectors() {
        assertEquals(
                java.util.Set.of("obsidian", "samgov", "github", "openhuman",
                        "embeddings", "gdrive", "onedrive"),
                ConnectorSettingsService.CONNECTORS.keySet());
    }
}
