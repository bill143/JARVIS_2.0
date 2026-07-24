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
    void catalogCoversAllTenConnectors() {
        assertEquals(
                java.util.Set.of("obsidian", "samgov", "github", "openhuman", "routing", "consensus",
                        "embeddings", "gdrive", "onedrive", "vision"),
                ConnectorSettingsService.CONNECTORS.keySet());
    }

    @Test
    void visionCatalogCoversAllVisionKeysWithCorrectSecrecy() {
        List<ConnectorSettingsService.FieldSpec> fields = ConnectorSettingsService.CONNECTORS.get("vision");
        java.util.Set<String> envs = fields.stream().map(ConnectorSettingsService.FieldSpec::env)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("JARVIS_VISION_MOTION_ENABLED", "JARVIS_VISION_MOTION_WEBHOOK_SECRET",
                "JARVIS_VISION_MOTION_COOLDOWN_SEC", "JARVIS_FACE_ENABLED", "JARVIS_FACE_PROVIDER",
                "JARVIS_FACE_BASE_URL", "JARVIS_FACE_API_KEY", "JARVIS_FACE_SIMILARITY_THRESHOLD",
                "JARVIS_FACE_PENDING_TTL_SEC"), envs);

        java.util.Set<String> secretKeys = fields.stream()
                .filter(ConnectorSettingsService.FieldSpec::secret)
                .map(ConnectorSettingsService.FieldSpec::key)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("vision.motion.webhookSecret", "vision.face.apiKey"), secretKeys);
    }

    @Test
    void consensusCatalogCoversAllConsensusKeys() {
        List<ConnectorSettingsService.FieldSpec> fields = ConnectorSettingsService.CONNECTORS.get("consensus");
        java.util.Set<String> envs = fields.stream().map(ConnectorSettingsService.FieldSpec::env)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("JARVIS_CONSENSUS_ENABLED", "JARVIS_CONSENSUS_MODE",
                "JARVIS_CONSENSUS_MAX_ROUNDS", "JARVIS_CONSENSUS_REQUIRE_RATIONALE",
                "JARVIS_CONSENSUS_TIMEOUT_MS"), envs);
        assertTrue(fields.stream().noneMatch(ConnectorSettingsService.FieldSpec::secret));
    }

    @Test
    void openHumanCatalogNowIncludesTheEnabledFlag() {
        List<ConnectorSettingsService.FieldSpec> fields = ConnectorSettingsService.CONNECTORS.get("openhuman");
        assertTrue(fields.stream().anyMatch(f -> f.key().equals("openhuman.enabled")
                && f.env().equals("JARVIS_OPENHUMAN_ENABLED") && !f.secret()));
    }

    @Test
    void routingCatalogCoversAllRoutingKeys() {
        List<ConnectorSettingsService.FieldSpec> fields = ConnectorSettingsService.CONNECTORS.get("routing");
        java.util.Set<String> envs = fields.stream().map(ConnectorSettingsService.FieldSpec::env)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("JARVIS_ROUTING_FAILOVER_ENABLED", "JARVIS_ROUTING_TIMEOUT_MS",
                "JARVIS_ROUTING_MAX_RETRIES", "JARVIS_ROUTING_BREAKER_FAIL_THRESHOLD",
                "JARVIS_ROUTING_BREAKER_WINDOW_SEC", "JARVIS_ROUTING_BREAKER_COOLDOWN_SEC"), envs);
        assertTrue(fields.stream().noneMatch(ConnectorSettingsService.FieldSpec::secret));
    }
}
