package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.api.JarvisApi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebServerTest {

    private WebServer server;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();

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
        // Feature pins: briefing + voice controls stay on the page.
        assertTrue(response.body().contains("BRIEFING"));
        assertTrue(response.body().contains("VOICE"));
        assertTrue(response.body().contains("INTERRUPT"));
        assertTrue(response.body().contains("speechSynthesis"));
    }

    @Test
    void statusReportsModeAndModel() throws Exception {
        HttpResponse<String> response = get("/status");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"online\":false"));
        assertTrue(response.body().contains("test-model"));
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
}
