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
        assertTrue(response.body().contains("PERSONAL INTELLIGENCE"));   // semantic recall page
        assertTrue(response.body().contains("data-nav=\"intel\""));     // and its nav entry
        assertTrue(response.body().contains("PROJECT DISCUSSION"));      // discussion page
        assertTrue(response.body().contains("data-nav=\"discussion\"")); // and its nav entry
        assertTrue(response.body().contains("data-nav=\"brain\""));      // BRAIN (Obsidian) tab
        assertTrue(response.body().contains("OBSIDIAN VAULT"));          // and its page
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
    void chatAcceptsAConversationModeAndStillAnswers() throws Exception {
        HttpResponse<String> response = post("/chat",
                "{\"prompt\":\"outline a plan\",\"mode\":\"research\"}");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"completed\":true"));
        // Offline echo returns the effective prompt, so the mode preamble is applied.
        assertTrue(response.body().contains("Mode: Research"));
        assertTrue(response.body().contains("outline a plan"));
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
                new AppWiring.Governance(null, null, broker, policy, null, null, null, null, null, null, null, null, null, null, null));
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
    void agentsRunEndpointExecutesTheMultiAgentPipeline() throws Exception {
        JarvisApi api = AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>());
        MultiAgentService svc = new MultiAgentService(api, null);
        WebServer wired = WebServer.start(api, false, "m", 0, new HardwareMonitor(), null, false, null,
                new com.jarvis.memory.InMemoryStore<>(), null, null,
                new AppWiring.Governance(null, null, null, null, null, null, null, null, null, null, svc, null, null, null, null));
        try {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + wired.port() + "/agents/run"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"goal\":\"plan a trip\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("PLANNER"));
            assertTrue(r.body().contains("CRITIC"));
            assertTrue(r.body().contains("\"result\":"));
        } finally {
            wired.stop();
        }
    }

    @Test
    void autonomousRunEndpointDrivesTheBudgetedGoalLoop() throws Exception {
        JarvisApi api = AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>());
        AutonomousService svc = new AutonomousService(api, null);
        WebServer wired = WebServer.start(api, false, "m", 0, new HardwareMonitor(), null, false, null,
                new com.jarvis.memory.InMemoryStore<>(), null, null,
                new AppWiring.Governance(null, null, null, null, null, null, null, null, null, null, null, svc, null, null, null));
        try {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + wired.port() + "/autonomous/run"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"goal\":\"tidy the workshop\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("\"goal\":\"tidy the workshop\""));
            assertTrue(r.body().contains("\"completed\":"));
            assertTrue(r.body().contains("\"steps\":["));
        } finally {
            wired.stop();
        }
    }

    @Test
    void autonomousRunEndpointIs503WhenNoServiceWired() throws Exception {
        assertEquals(503, post("/autonomous/run", "{\"goal\":\"x\"}").statusCode());
    }

    @Test
    void discussionRunEndpointChairsABoundedAdvisorLoop() throws Exception {
        // A fake OpenHuman core: /health ok, /rpc returns a canned advisor reply.
        com.jarvis.integrations.openhuman.OpenHumanTransport fake = (method, path, body) -> {
            if (path.equals("/health")) {
                return new com.jarvis.integrations.openhuman.OpenHumanResponse(200, "{}");
            }
            return new com.jarvis.integrations.openhuman.OpenHumanResponse(200,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"text\":\"advisor reply\"}}");
        };
        com.jarvis.integrations.openhuman.OpenHumanClient advisor =
                new com.jarvis.integrations.openhuman.OpenHumanClient(fake);   // fake.available()==true
        JarvisApi api = AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>());
        DiscussionService svc = new DiscussionService(api, advisor, null,
                new com.jarvis.memory.InMemoryRecordStore());
        WebServer wired = WebServer.start(api, false, "m", 0, new HardwareMonitor(), null, false, null,
                new com.jarvis.memory.InMemoryStore<>(), null, null,
                new AppWiring.Governance(null, null, null, null, null, null, null, null, null, null, null, null, null, svc, null));
        try {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + wired.port() + "/discussion/run"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"topic\":\"go-kart budget\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("\"topic\":\"go-kart budget\""));
            assertTrue(r.body().contains("\"rounds\":["));
            assertTrue(r.body().contains("advisor reply"));
            assertTrue(r.body().contains("\"advisorAvailable\":true"));
        } finally {
            wired.stop();
        }
    }

    @Test
    void discussionRunEndpointIs503WhenNoServiceWired() throws Exception {
        assertEquals(503, post("/discussion/run", "{\"topic\":\"x\"}").statusCode());
    }

    @Test
    void semanticEndpointsRememberRecallAndReportKeywordFallback() throws Exception {
        // Dormant embeddings (no key) → keyword fallback; recall still works and mode is reported.
        SemanticMemoryService svc = new SemanticMemoryService(
                new com.jarvis.memory.InMemoryRecordStore(),
                com.jarvis.rag.EmbeddingProvider.DORMANT, null);
        WebServer wired = WebServer.start(
                AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>()),
                false, "m", 0, new HardwareMonitor(), null, false, null,
                new com.jarvis.memory.InMemoryStore<>(), null, null,
                new AppWiring.Governance(null, null, null, null, null, null, null, null, null, null, null, null, svc, null, null));
        try {
            String b = "http://localhost:" + wired.port();
            post2(b + "/semantic",
                    "{\"action\":\"add\",\"title\":\"Go-kart\",\"content\":\"brushless motor and battery\"}");
            HttpResponse<String> list = client.send(
                    HttpRequest.newBuilder(URI.create(b + "/semantic")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(list.body().contains("Go-kart"));
            assertTrue(list.body().contains("\"mode\":\"keyword\""));
            HttpResponse<String> found = client.send(HttpRequest.newBuilder(
                            URI.create(b + "/semantic/search?q=battery+brushless&k=5")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(found.body().contains("Go-kart"));
            assertTrue(found.body().contains("\"score\":"));
            assertTrue(found.body().contains("\"mode\":\"keyword\""));
        } finally {
            wired.stop();
        }
    }

    @Test
    void knowledgeBaseEndpointsAddSearchAndList() throws Exception {
        com.jarvis.kb.KnowledgeBase kb =
                new com.jarvis.kb.KnowledgeBase(new com.jarvis.memory.InMemoryRecordStore());
        WebServer wired = WebServer.start(
                AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>()),
                false, "m", 0, new HardwareMonitor(), null, false, null,
                new com.jarvis.memory.InMemoryStore<>(), null, null,
                new AppWiring.Governance(null, null, null, null, null, null, null, null, null, kb, null, null, null, null, null));
        try {
            String base = "http://localhost:" + wired.port();
            post2(base + "/kb",
                    "{\"action\":\"add\",\"title\":\"Go-kart\",\"content\":\"brushless motor and battery\"}");
            assertTrue(client.send(HttpRequest.newBuilder(URI.create(base + "/kb")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body().contains("Go-kart"));
            HttpResponse<String> found = client.send(HttpRequest.newBuilder(
                            URI.create(base + "/kb/search?q=brushless+battery&k=5")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(found.body().contains("Go-kart"));
            assertTrue(found.body().contains("\"score\":"));
        } finally {
            wired.stop();
        }
    }

    @Test
    void brainEndpointsListSearchOpenAndBlockTraversal() throws Exception {
        java.nio.file.Files.writeString(tmp.resolve("Go-kart.md"),
                "# Go-kart\nbrushless motor and battery pack");
        java.nio.file.Files.createDirectories(tmp.resolve("sub"));
        java.nio.file.Files.writeString(tmp.resolve("sub/Recipe.md"), "# Recipe\ntomato sauce");
        BrainVault brain = BrainVault.fromConfig(tmp.toString(), true, null);
        WebServer wired = WebServer.start(
                AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>()),
                false, "m", 0, new HardwareMonitor(), null, false, null,
                new com.jarvis.memory.InMemoryStore<>(), null, null,
                new AppWiring.Governance(null, null, null, null, null, null, null, null, null, null, null, null, null, null, brain));
        try {
            String b = "http://localhost:" + wired.port();
            HttpResponse<String> status = client.send(
                    HttpRequest.newBuilder(URI.create(b + "/brain")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(status.body().contains("\"configured\":true"));
            assertTrue(status.body().contains("\"readOnly\":true"));
            assertTrue(status.body().contains("Go-kart.md"));

            HttpResponse<String> found = client.send(HttpRequest.newBuilder(
                            URI.create(b + "/brain/search?q=brushless+battery&k=5")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(found.body().contains("Go-kart"));

            HttpResponse<String> note = client.send(HttpRequest.newBuilder(
                            URI.create(b + "/brain/note?path=" + java.net.URLEncoder.encode(
                                    "sub/Recipe.md", java.nio.charset.StandardCharsets.UTF_8)))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, note.statusCode());
            assertTrue(note.body().contains("tomato sauce"));

            // Path traversal is refused (403), not served.
            HttpResponse<String> escape = client.send(HttpRequest.newBuilder(
                            URI.create(b + "/brain/note?path=" + java.net.URLEncoder.encode(
                                    "../secret.md", java.nio.charset.StandardCharsets.UTF_8)))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, escape.statusCode());
        } finally {
            wired.stop();
        }
    }

    @Test
    void brainEndpointReportsUnconfiguredEmptyStateGracefully() throws Exception {
        BrainVault brain = BrainVault.fromConfig(null, true, null);   // no vault
        WebServer wired = WebServer.start(
                AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>()),
                false, "m", 0, new HardwareMonitor(), null, false, null,
                new com.jarvis.memory.InMemoryStore<>(), null, null,
                new AppWiring.Governance(null, null, null, null, null, null, null, null, null, null, null, null, null, null, brain));
        try {
            String b = "http://localhost:" + wired.port();
            assertTrue(client.send(HttpRequest.newBuilder(URI.create(b + "/brain")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body().contains("\"configured\":false"));
            // Note endpoint is 503 when no vault is connected.
            assertEquals(503, client.send(HttpRequest.newBuilder(
                            URI.create(b + "/brain/note?path=x.md")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            wired.stop();
        }
    }

    @Test
    void workflowsEndpointSavesRunsAndRecordsHistory() throws Exception {
        JarvisApi api = AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>());
        WorkflowService svc = new WorkflowService(new com.jarvis.memory.InMemoryRecordStore(),
                new com.jarvis.memory.InMemoryRecordStore(), api, null);
        WebServer wired = WebServer.start(api, false, "m", 0, new HardwareMonitor(), null, false, null,
                new com.jarvis.memory.InMemoryStore<>(), null, null,
                new AppWiring.Governance(null, null, null, null, null, null, null, null, svc, null, null, null, null, null, null));
        try {
            String base = "http://localhost:" + wired.port();
            post2(base + "/workflows",
                    "{\"action\":\"save\",\"name\":\"Brief\",\"steps\":[\"say hello\"],\"trigger\":\"MANUAL\"}");
            HttpResponse<String> defs = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/workflows")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(defs.body().contains("Brief"));

            String id = svc.list().get(0).id();
            post2(base + "/workflows", "{\"action\":\"run\",\"id\":\"" + id + "\"}");
            HttpResponse<String> runs = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/workflows/runs")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(runs.body().contains("Brief"));
            assertTrue(runs.body().contains("say hello"));   // step recorded in history
        } finally {
            wired.stop();
        }
    }

    @Test
    void tasksEndpointCreatesMovesAndListsWithBlockedFlag() throws Exception {
        com.jarvis.tasks.TaskBoard board =
                new com.jarvis.tasks.TaskBoard(new com.jarvis.memory.InMemoryRecordStore());
        WebServer wired = WebServer.start(
                AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>()),
                false, "m", 0, new HardwareMonitor(), null, false, null,
                new com.jarvis.memory.InMemoryStore<>(), null, null,
                new AppWiring.Governance(null, null, null, null, null, null, null, board, null, null, null, null, null, null, null));
        try {
            String base = "http://localhost:" + wired.port() + "/tasks";
            post2(base, "{\"action\":\"create\",\"title\":\"first task\"}");
            HttpResponse<String> listed = client.send(
                    HttpRequest.newBuilder(URI.create(base)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(listed.body().contains("first task"));
            assertTrue(listed.body().contains("\"status\":\"TODO\""));
            assertTrue(listed.body().contains("\"blocked\":false"));

            String id = board.list().get(0).id();
            post2(base, "{\"action\":\"move\",\"id\":\"" + id + "\",\"status\":\"DONE\"}");
            assertEquals(com.jarvis.tasks.TaskStatus.DONE, board.list().get(0).status());
        } finally {
            wired.stop();
        }
    }

    private void post2(String url, String json) throws Exception {
        client.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void memoryEndpointListsAddsAndDeletesPreferences() throws Exception {
        com.jarvis.memory.MemoryStore<String> memory = new com.jarvis.memory.InMemoryStore<>();
        memory.put("about", "me", "I'm Bill.");
        WebServer wired = WebServer.start(
                AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>()),
                true, "m", 0, new HardwareMonitor(), null, true, null, memory);
        try {
            String base = "http://localhost:" + wired.port() + "/memory";
            // add a preference
            client.send(HttpRequest.newBuilder(URI.create(base))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"action\":\"add\",\"value\":\"prefers metric units\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> listed = client.send(
                    HttpRequest.newBuilder(URI.create(base)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(listed.body().contains("prefers metric units"));
            assertTrue(listed.body().contains("I'm Bill."));   // about surfaced

            String key = memory.query("preferences").get(0).key();
            client.send(HttpRequest.newBuilder(URI.create(base))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"action\":\"delete\",\"key\":\"" + key + "\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(memory.query("preferences").isEmpty());
        } finally {
            wired.stop();
        }
    }

    @Test
    void onboardingCompletionFlagRoundTripsThroughMemory() throws Exception {
        com.jarvis.memory.MemoryStore<String> memory = new com.jarvis.memory.InMemoryStore<>();
        WebServer wired = WebServer.start(
                AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>()),
                true, "m", 0, new HardwareMonitor(), null, true, null, memory);
        try {
            String base = "http://localhost:" + wired.port();
            assertTrue(client.send(
                    HttpRequest.newBuilder(URI.create(base + "/onboarding")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body().contains("\"completed\":false"));

            client.send(HttpRequest.newBuilder(URI.create(base + "/onboarding/complete"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertTrue(client.send(
                    HttpRequest.newBuilder(URI.create(base + "/onboarding")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body().contains("\"completed\":true"));
            assertEquals("true", memory.get("app", "onboarded").orElseThrow().value());
        } finally {
            wired.stop();
        }
    }

    @Test
    void usageEndpointReportsMeteredCallsSummaryAndEvents() throws Exception {
        com.jarvis.metering.UsageMeter meter = new com.jarvis.metering.UsageMeter(
                new com.jarvis.memory.InMemoryRecordStore(), com.jarvis.metering.PriceTable.defaults());
        meter.record("anthropic", "claude-sonnet-5", 1000, 500);

        WebServer wired = WebServer.start(
                AppWiring.buildApi(null, "m", new com.jarvis.memory.InMemoryStore<>()),
                false, "m", 0, new HardwareMonitor(), null, false, null,
                new com.jarvis.memory.InMemoryStore<>(), null, null,
                new AppWiring.Governance(null, null, null, null, null, null, meter, null, null, null, null, null, null, null, null));
        try {
            String body = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + wired.port() + "/usage"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(body.contains("\"calls\":1"));
            assertTrue(body.contains("\"inputTokens\":1000"));
            assertTrue(body.contains("\"costUsd\":"));
            assertTrue(body.contains("claude-sonnet-5"));   // event surfaced
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
                new AppWiring.Governance(null, null, null, null, null, mgr, null, null, null, null, null, null, null, null, null));
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
                new AppWiring.Governance(null, null, null, null, checker, null, null, null, null, null, null, null, null, null, null));
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
                new AppWiring.Governance(null, plugins, null, null, null, null, null, null, null, null, null, null, null, null, null));
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
                new AppWiring.Governance(log, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
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
                new AppWiring.Governance(log, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
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
