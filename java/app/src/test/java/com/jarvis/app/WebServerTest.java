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
                new com.jarvis.memory.InMemoryStore<>(), people, null, null);
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
                new com.jarvis.memory.InMemoryStore<>(), null, null, log);
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
