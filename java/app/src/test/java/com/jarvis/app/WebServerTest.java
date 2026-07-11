package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.api.JarvisApi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebServerTest {

    private WebServer server;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();

    @TempDir
    Path tmp;

    @BeforeEach
    void startServer() throws Exception {
        JarvisApi api = AppWiring.buildApi(null, "test-model", new com.jarvis.memory.InMemoryStore<>());
        server = WebServer.start(api, false, "test-model", 0);
        base = "http://localhost:" + server.port();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void rootServesTheDashboard() throws Exception {
        HttpResponse<String> response = get("/");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("J.A.R.V.I.S."));
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/html"));
        // Feature pins: briefing + voice controls + HUD stay on the page.
        assertTrue(response.body().contains("BRIEFING"));
        assertTrue(response.body().contains("VOICE"));
        assertTrue(response.body().contains("id=\"interrupt\""));
        assertTrue(response.body().contains("speechSynthesis"));
        assertTrue(response.body().contains("id=\"orb\""));       // the HUD orb
        assertTrue(response.body().contains("/telemetry"));       // live metrics wiring
    }

    @Test
    void statusReportsModeAndModel() throws Exception {
        HttpResponse<String> response = get("/status");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"online\":false"));
        assertTrue(response.body().contains("test-model"));
        assertTrue(response.body().contains("\"google\":"));   // integration status reported
    }

    @Test
    void chatAnswersThroughTheApi() throws Exception {
        HttpResponse<String> response = post("/chat", "{\"prompt\":\"hello dashboard\"}");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"completed\":true"));
        assertTrue(response.body().contains("hello dashboard"));
    }

    @Test
    void chatRejectsBadRequests() throws Exception {
        assertEquals(400, post("/chat", "{\"prompt\":\"\"}").statusCode());
        assertEquals(400, post("/chat", "not json").statusCode());
        assertEquals(405, get("/chat").statusCode());
    }

    @Test
    void offlineChatNeverClaimsToBeOnline() throws Exception {
        HttpResponse<String> response = post("/chat", "{\"prompt\":\"are you there\"}");
        assertFalse(response.body().contains("Online and ready"));
    }

    @Test
    void telemetryEndpointReturnsLiveMetrics() throws Exception {
        HttpResponse<String> response = get("/telemetry");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("cpu"));
        assertTrue(response.body().contains("ram"));
        assertTrue(response.body().contains("cores"));
    }

    @Test
    void alertsEndpointReturnsJsonArray() throws Exception {
        HttpResponse<String> response = get("/alerts");
        assertEquals(200, response.statusCode());
        assertEquals("[]", response.body());   // no monitor wired in this test
    }

    @Test
    void visionEndpointReportsUnavailableWithoutAKey() throws Exception {
        HttpResponse<String> response = post("/vision", "{\"image\":\"AQID\",\"question\":\"hi\"}");
        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("API key"));
    }

    @Test
    void dashboardExposesTheNewControls() throws Exception {
        String body = get("/").body();
        assertTrue(body.contains("CAMERA"));
        assertTrue(body.contains("SETTINGS"));
        assertTrue(body.contains("id=\"s_lang\""));   // language now lives in the settings drawer
        assertTrue(body.contains("id=\"drawer\""));
        assertTrue(body.contains("MAIL"));            // workspace tabs
        assertTrue(body.contains("CALENDAR"));
        assertTrue(body.contains("id=\"wsp\""));
    }

    @Test
    void mailAndCalendarReturn503WhenGoogleNotWired() throws Exception {
        assertEquals(503, get("/mail").statusCode());
        assertEquals(503, get("/calendar").statusCode());
    }

    @Test
    void instructionsRoundTripThroughMemory() throws Exception {
        com.jarvis.memory.MemoryStore<String> memory = new com.jarvis.memory.InMemoryStore<>();
        WebServer wired = WebServer.start(
                AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>()),
                true, "m", 0, new HardwareMonitor(), null, true, null, memory);
        try {
            String base = "http://localhost:" + wired.port() + "/instructions";
            client.send(HttpRequest.newBuilder(URI.create(base))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"area\":\"mail\",\"text\":\"flag client emails\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> got = client.send(
                    HttpRequest.newBuilder(URI.create(base)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(got.body().contains("flag client emails"));
            assertEquals("flag client emails", memory.get("instructions", "mail").orElseThrow().value());
        } finally {
            wired.stop();
        }
    }

    @Test
    void peopleUpdateRoutesThroughThePostHandler() throws Exception {
        PeopleStore people = new PeopleStore(tmp.resolve("people.json"));
        WebServer wired = WebServer.start(
                AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>()),
                false, "m", 0, new HardwareMonitor(), null, false, null,
                new com.jarvis.memory.InMemoryStore<>(), people, null, (AppWiring.Governance) null);
        try {
            String b = "http://localhost:" + wired.port() + "/people";
            client.send(HttpRequest.newBuilder(URI.create(b))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"name\":\"Rich\",\"email\":\"old@x.com\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            String id = people.all().get(0).id();

            HttpResponse<String> upd = client.send(HttpRequest.newBuilder(URI.create(b))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"id\":\"" + id + "\",\"name\":\"Richard\","
                                            + "\"email\":\"richard@x.com\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, upd.statusCode());
            assertEquals(1, people.all().size());              // updated in place, not appended
            assertEquals("Richard", people.all().get(0).name());
            assertEquals("richard@x.com", people.all().get(0).email());
            assertTrue(upd.body().contains("Richard"));        // summaries reflect the change
        } finally {
            wired.stop();
        }
    }

    @Test
    void permissionsEndpointsSurfacePendingRequestsAndResolveThem() throws Exception {
        com.jarvis.security.PermissionBroker broker = new com.jarvis.security.PermissionBroker(3_000);
        com.jarvis.security.PermissionPolicy policy = new com.jarvis.security.PermissionPolicy();

        WebServer wired = WebServer.start(
                AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>()),
                false, "m", 0, new HardwareMonitor(), null, false, null,
                new com.jarvis.memory.InMemoryStore<>(), null, null,
                new AppWiring.Governance(null, null, broker, policy, null, null));
        try {
            String base = "http://localhost:" + wired.port();
            // A tool thread asks for approval; it will block until we answer via the endpoint.
            var pending = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                    broker.request("email_send", com.jarvis.tools.RiskTier.DESTRUCTIVE, "args: [to]"));

            String id = null;
            for (int i = 0; i < 100 && id == null; i++) {
                HttpResponse<String> list = client.send(
                        HttpRequest.newBuilder(URI.create(base + "/permissions/pending")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (list.body().contains("email_send")) {
                    id = list.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
                } else {
                    Thread.sleep(10);
                }
            }
            assertTrue(id != null && id.startsWith("perm-"));

            HttpResponse<String> decide = client.send(HttpRequest.newBuilder(
                            URI.create(base + "/permissions/decide"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"id\":\"" + id + "\",\"allow\":true}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(decide.body().contains("\"ok\":true"));
            assertEquals(com.jarvis.security.PermissionOutcome.ALLOWED,
                    pending.get(3, java.util.concurrent.TimeUnit.SECONDS));

            // The config endpoint reflects and updates the level.
            HttpResponse<String> cfg = client.send(HttpRequest.newBuilder(
                            URI.create(base + "/permissions/config"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"level\":\"MUTATING\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(cfg.body().contains("MUTATING"));
            assertEquals(com.jarvis.security.PermissionLevel.MUTATING, policy.level());
        } finally {
            wired.stop();
        }
    }

    @Test
    void licenseEndpointsActivateVerifyAndReportStatus() throws Exception {
        java.security.KeyPair kp = java.security.KeyPairGenerator.getInstance("RSA").genKeyPair();
        com.jarvis.licensing.EncryptedLicenseStore store =
                new com.jarvis.licensing.EncryptedLicenseStore(tmp.resolve("license.dat"));
        com.jarvis.licensing.LicenseManager mgr = new com.jarvis.licensing.LicenseManager(
                new com.jarvis.licensing.LicenseVerifier(kp.getPublic()), store);
        String key = com.jarvis.licensing.LicenseSigner.sign(
                new com.jarvis.licensing.License("Bill", "b@x.com", "standard",
                        java.time.Instant.parse("2026-01-01T00:00:00Z"), null),
                kp.getPrivate());

        WebServer wired = WebServer.start(
                AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>()),
                false, "m", 0, new HardwareMonitor(), null, false, null,
                new com.jarvis.memory.InMemoryStore<>(), null, null,
                new AppWiring.Governance(null, null, null, null, null, mgr));
        try {
            String base = "http://localhost:" + wired.port();
            HttpResponse<String> before = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/license")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(before.body().contains("\"state\":\"UNLICENSED\""));
            assertTrue(before.body().contains("\"locked\":true"));

            HttpResponse<String> activated = client.send(HttpRequest.newBuilder(
                            URI.create(base + "/license/activate"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"key\":\"" + key + "\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(activated.body().contains("\"state\":\"LICENSED\""));
            assertTrue(activated.body().contains("\"licensee\":\"Bill\""));
            assertTrue(activated.body().contains("\"locked\":false"));

            // A bogus key is rejected (INVALID), not accepted.
            HttpResponse<String> bad = client.send(HttpRequest.newBuilder(
                            URI.create(base + "/license/activate"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"key\":\"nope.nope\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(bad.body().contains("\"state\":\"INVALID\""));
        } finally {
            wired.stop();
        }
    }

    @Test
    void licenseEndpointReportsDevWhenNoManagerWired() throws Exception {
        assertTrue(get("/license").body().contains("\"state\":\"DEV\""));
    }

    @Test
    void updateEndpointReportsAVerifiedAvailableUpdate() throws Exception {
        java.security.KeyPair kp = java.security.KeyPairGenerator.getInstance("RSA").genKeyPair();
        com.jarvis.updater.ManifestSource source = () -> com.jarvis.updater.ManifestSigner.sign(
                new com.jarvis.updater.UpdateManifest(
                        "9.9.9", "https://example.com/JARVIS-9.9.9.msi", "Big update"),
                kp.getPrivate());
        com.jarvis.updater.UpdateChecker checker = new com.jarvis.updater.UpdateChecker(
                com.jarvis.updater.Version.parse("0.1.0"), source,
                new com.jarvis.updater.ManifestVerifier(kp.getPublic()));
        checker.check();   // populate latest()

        WebServer wired = WebServer.start(
                AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>()),
                false, "m", 0, new HardwareMonitor(), null, false, null,
                new com.jarvis.memory.InMemoryStore<>(), null, null,
                new AppWiring.Governance(null, null, null, null, checker, null));
        try {
            String body = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + wired.port() + "/update"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(body.contains("\"state\":\"UPDATE_AVAILABLE\""));
            assertTrue(body.contains("\"version\":\"9.9.9\""));
            assertTrue(body.contains("JARVIS-9.9.9.msi"));
        } finally {
            wired.stop();
        }
    }

    @Test
    void updateEndpointReportsDisabledWhenNoCheckerIsWired() throws Exception {
        WebServer wired = WebServer.start(
                AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>()),
                false, "m", 0, new HardwareMonitor(), null, false, null,
                new com.jarvis.memory.InMemoryStore<>(), null, null, (AppWiring.Governance) null);
        try {
            String body = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + wired.port() + "/update"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(body.contains("\"state\":\"DISABLED\""));
        } finally {
            wired.stop();
        }
    }

    @Test
    void toolsEndpointExposesManifestsRiskTiersAndHealth() throws Exception {
        com.jarvis.registry.PluginRegistry plugins = new com.jarvis.registry.PluginRegistry(
                java.util.List.of(
                        new com.jarvis.registry.ToolManifest("clock", "the time",
                                com.jarvis.tools.RiskTier.READ_ONLY, java.util.List.of()),
                        new com.jarvis.registry.ToolManifest("email_send", "send mail",
                                com.jarvis.tools.RiskTier.DESTRUCTIVE, java.util.List.of())));

        WebServer wired = WebServer.start(
                AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>()),
                false, "m", 0, new HardwareMonitor(), null, false, null,
                new com.jarvis.memory.InMemoryStore<>(), null, null,
                new AppWiring.Governance(null, plugins, null, null, null, null));
        try {
            HttpResponse<String> r = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + wired.port() + "/tools"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("email_send"));
            assertTrue(r.body().contains("DESTRUCTIVE"));
            assertTrue(r.body().contains("READ_ONLY"));
            assertTrue(r.body().contains("OPERATIONAL"));   // health present
        } finally {
            wired.stop();
        }
    }

    @Test
    void auditEndpointExposesTheFieldsTheHudActivityFeedClassifiesOn() throws Exception {
        // The HUD feed colours lines by category + outcome and labels by riskTier — guard those
        // fields stay in the /audit payload so the feed never silently goes blank.
        com.jarvis.audit.AuditLog log =
                new com.jarvis.audit.RecordStoreAuditLog(new com.jarvis.memory.InMemoryRecordStore());
        log.record(new com.jarvis.audit.AuditEvent(
                com.jarvis.audit.AuditCategory.SYSTEM, "power", com.jarvis.audit.AuditTrigger.USER,
                com.jarvis.tools.RiskTier.DESTRUCTIVE, com.jarvis.audit.AuditOutcome.FAILURE,
                "permission prompt -> DENIED"));

        WebServer wired = WebServer.start(
                AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>()),
                false, "m", 0, new HardwareMonitor(), null, false, null,
                new com.jarvis.memory.InMemoryStore<>(), null, null,
                new AppWiring.Governance(log, null, null, null, null, null));
        try {
            String body = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + wired.port() + "/audit"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(body.contains("\"category\":\"SYSTEM\""));
            assertTrue(body.contains("\"outcome\":\"FAILURE\""));
            assertTrue(body.contains("\"riskTier\":\"DESTRUCTIVE\""));
            assertTrue(body.contains("\"action\":\"power\""));
            assertTrue(body.contains("\"at\":"));
        } finally {
            wired.stop();
        }
    }

    @Test
    void auditEndpointReturnsRecordedEventsNewestFirstWithFilters() throws Exception {
        com.jarvis.audit.AuditLog log =
                new com.jarvis.audit.RecordStoreAuditLog(new com.jarvis.memory.InMemoryRecordStore());
        log.record(com.jarvis.audit.AuditEvent.toolSuccess(
                "clock", com.jarvis.tools.RiskTier.READ_ONLY, "-"));
        log.record(new com.jarvis.audit.AuditEvent(
                com.jarvis.audit.AuditCategory.DESTRUCTIVE_ACTION, "email_trash",
                com.jarvis.audit.AuditTrigger.USER, com.jarvis.tools.RiskTier.DESTRUCTIVE,
                com.jarvis.audit.AuditOutcome.SUCCESS, "args: [id]"));

        WebServer wired = WebServer.start(
                AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>()),
                false, "m", 0, new HardwareMonitor(), null, false, null,
                new com.jarvis.memory.InMemoryStore<>(), null, null,
                new AppWiring.Governance(log, null, null, null, null, null));
        try {
            String base = "http://localhost:" + wired.port() + "/audit";
            HttpResponse<String> all = client.send(
                    HttpRequest.newBuilder(URI.create(base)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, all.statusCode());
            // Newest first: email_trash appears before clock in the payload.
            assertTrue(all.body().indexOf("email_trash") < all.body().indexOf("clock"));

            HttpResponse<String> destructive = client.send(
                    HttpRequest.newBuilder(URI.create(base + "?risk=DESTRUCTIVE")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(destructive.body().contains("email_trash"));
            assertFalse(destructive.body().contains("clock"));
        } finally {
            wired.stop();
        }
    }

    @Test
    void configEndpointRoundTripsThresholds() throws Exception {
        HardwareMonitor monitor = new HardwareMonitor();
        WebServer wired = WebServer.start(
                AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>()),
                true, "m", 0, monitor, null);
        try {
            String base = "http://localhost:" + wired.port();
            HttpResponse<String> post = client.send(HttpRequest.newBuilder(URI.create(base + "/config"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"cpuThreshold\":75,\"ramThreshold\":80}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, post.statusCode());
            assertTrue(post.body().contains("75"));
            assertEquals(75.0, monitor.cpuThreshold());
            assertEquals(80.0, monitor.ramThreshold());
        } finally {
            wired.stop();
        }
    }

    @Test
    void visionEndpointAnalyzesAWebcamFrameWhenWired() throws Exception {
        JarvisApi api = AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>());
        HardwareMonitor monitor = new HardwareMonitor();
        WebServer wired = WebServer.start(api, true, "m", 0, monitor,
                (png, question) -> "I see a keyboard, sir. (" + png.length + " bytes)");
        try {
            String url = "http://localhost:" + wired.port() + "/vision";
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(url))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"image\":\"AQID\",\"question\":\"what is this\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("keyboard"));
        } finally {
            wired.stop();
        }
    }
}
