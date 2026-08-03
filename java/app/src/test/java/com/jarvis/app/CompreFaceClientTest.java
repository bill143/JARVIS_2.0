package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link CompreFaceClient} against a real local {@link HttpServer} (the same JDK class
 * {@code WebServer} uses in production) standing in for CompreFace — there is no mocking framework
 * on this project's whitelist, and the class under test genuinely performs HTTP I/O through an
 * injected {@link HttpClient}, so a hand-written fake server is the zero-dependency way to test it.
 *
 * <p>A fresh server bound to an ephemeral port is created per test in {@link #setUp} and torn down
 * in {@link #tearDown} to avoid any port-reuse flakiness between test methods.
 */
class CompreFaceClientTest {

    private HttpServer server;
    private int port;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static VisionSettings.FaceSnapshot snapshot(String baseUrl, String apiKey, double threshold) {
        return new VisionSettings.FaceSnapshot(true, "compreface", baseUrl, apiKey, threshold, 300);
    }

    private CompreFaceClient client(VisionSettings.FaceSnapshot snap) {
        return new CompreFaceClient(() -> snap, httpClient);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * Reserves and immediately releases an ephemeral port so nothing is bound there — used by the
     * connection-failure tests. Stopping the shared {@link #server} instead was tried first, but on
     * Windows the just-closed socket can linger in a state that still completes the TCP handshake
     * without ever responding, so the request stalls until the 30s request timeout rather than
     * failing fast with "connection refused".
     */
    private static int unboundPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    @Test
    void recognizeMatchedAboveThresholdReturnsSubjectAndSimilarity() throws IOException {
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        server.createContext("/api/v1/recognition/recognize", exchange -> {
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            exchange.getRequestBody().readAllBytes();
            sendJson(exchange, 200,
                    "{\"result\":[{\"subjects\":[{\"subject\":\"alice\",\"similarity\":0.97625}]}]}");
        });
        server.start();

        FaceRecognitionClient.FaceMatchResult result =
                client(snapshot(baseUrl(), "test-key", 0.80)).recognize(new byte[] {1, 2, 3});

        assertEquals(FaceRecognitionClient.FaceMatchResult.Status.MATCHED, result.status());
        assertEquals("alice", result.subjectId());
        assertEquals(0.97625, result.similarity(), 0.0001);
        assertEquals("test-key", apiKeyHeader.get());
    }

    @Test
    void recognizeBelowThresholdIsNoMatch() throws IOException {
        server.createContext("/api/v1/recognition/recognize", exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendJson(exchange, 200, "{\"result\":[{\"subjects\":[{\"subject\":\"bob\",\"similarity\":0.5}]}]}");
        });
        server.start();

        FaceRecognitionClient.FaceMatchResult result =
                client(snapshot(baseUrl(), "test-key", 0.80)).recognize(new byte[] {1, 2, 3});

        assertEquals(FaceRecognitionClient.FaceMatchResult.Status.NO_MATCH, result.status());
    }

    @Test
    void recognizeEmptyResultArrayIsNoMatch() throws IOException {
        server.createContext("/api/v1/recognition/recognize", exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendJson(exchange, 200, "{\"result\":[]}");
        });
        server.start();

        FaceRecognitionClient.FaceMatchResult result =
                client(snapshot(baseUrl(), "test-key", 0.80)).recognize(new byte[] {1, 2, 3});

        assertEquals(FaceRecognitionClient.FaceMatchResult.Status.NO_MATCH, result.status());
    }

    @Test
    void recognizeEmptySubjectsArrayIsNoMatch() throws IOException {
        server.createContext("/api/v1/recognition/recognize", exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendJson(exchange, 200, "{\"result\":[{\"subjects\":[]}]}");
        });
        server.start();

        FaceRecognitionClient.FaceMatchResult result =
                client(snapshot(baseUrl(), "test-key", 0.80)).recognize(new byte[] {1, 2, 3});

        assertEquals(FaceRecognitionClient.FaceMatchResult.Status.NO_MATCH, result.status());
    }

    @Test
    void recognizeNon200ResponseIsError() throws IOException {
        server.createContext("/api/v1/recognition/recognize", exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendJson(exchange, 500, "{\"message\":\"boom\"}");
        });
        server.start();

        FaceRecognitionClient.FaceMatchResult result =
                client(snapshot(baseUrl(), "test-key", 0.80)).recognize(new byte[] {1, 2, 3});

        assertEquals(FaceRecognitionClient.FaceMatchResult.Status.ERROR, result.status());
        assertNotNull(result.message());
    }

    @Test
    void recognizeNotConfiguredMakesNoNetworkCall() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/api/v1/recognition/recognize", exchange -> {
            hits.incrementAndGet();
            sendJson(exchange, 200, "{\"result\":[]}");
        });
        // Deliberately never started: a network call here would refuse to connect anyway, but the
        // real assertion is that the client never even tries when unconfigured.

        FaceRecognitionClient.FaceMatchResult result =
                client(snapshot(null, null, 0.80)).recognize(new byte[] {1, 2, 3});

        assertEquals(FaceRecognitionClient.FaceMatchResult.Status.ERROR, result.status());
        assertEquals("CompreFace not configured", result.message());
        assertEquals(0, hits.get());
    }

    @Test
    void recognizeBlankApiKeyMakesNoNetworkCall() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/api/v1/recognition/recognize", exchange -> {
            hits.incrementAndGet();
            sendJson(exchange, 200, "{\"result\":[]}");
        });

        FaceRecognitionClient.FaceMatchResult result =
                client(snapshot(baseUrl(), "  ", 0.80)).recognize(new byte[] {1, 2, 3});

        assertEquals(FaceRecognitionClient.FaceMatchResult.Status.ERROR, result.status());
        assertEquals(0, hits.get());
    }

    @Test
    void recognizeEmptyImageBytesIsErrorWithoutNetworkCall() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/api/v1/recognition/recognize", exchange -> {
            hits.incrementAndGet();
            sendJson(exchange, 200, "{\"result\":[]}");
        });
        server.start();

        FaceRecognitionClient.FaceMatchResult result =
                client(snapshot(baseUrl(), "test-key", 0.80)).recognize(new byte[0]);

        assertEquals(FaceRecognitionClient.FaceMatchResult.Status.ERROR, result.status());
        assertEquals(0, hits.get());
    }

    @Test
    void recognizeConnectionFailureIsError() throws IOException {
        String unreachable = "http://localhost:" + unboundPort();

        FaceRecognitionClient.FaceMatchResult result =
                client(snapshot(unreachable, "test-key", 0.80)).recognize(new byte[] {1, 2, 3});

        assertEquals(FaceRecognitionClient.FaceMatchResult.Status.ERROR, result.status());
        assertNotNull(result.message());
    }

    @Test
    void enrollSuccessReturnsImageId() throws IOException {
        server.createContext("/api/v1/recognition/faces", exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendJson(exchange, 200, "{\"image_id\":\"abc-123\",\"subject\":\"alice\"}");
        });
        server.start();

        FaceRecognitionClient.FaceEnrollResult result =
                client(snapshot(baseUrl(), "test-key", 0.80)).enroll(new byte[] {1, 2, 3}, "alice");

        assertTrue(result.success());
        assertEquals("abc-123", result.subjectId());
    }

    @Test
    void enrollNon200ResponseIsFailure() throws IOException {
        server.createContext("/api/v1/recognition/faces", exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendJson(exchange, 400, "{\"message\":\"bad request\"}");
        });
        server.start();

        FaceRecognitionClient.FaceEnrollResult result =
                client(snapshot(baseUrl(), "test-key", 0.80)).enroll(new byte[] {1, 2, 3}, "alice");

        assertFalse(result.success());
        assertNotNull(result.reason());
    }

    @Test
    void enrollMalformedJsonIsFailure() throws IOException {
        server.createContext("/api/v1/recognition/faces", exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendJson(exchange, 200, "not json");
        });
        server.start();

        FaceRecognitionClient.FaceEnrollResult result =
                client(snapshot(baseUrl(), "test-key", 0.80)).enroll(new byte[] {1, 2, 3}, "alice");

        assertFalse(result.success());
        assertNotNull(result.reason());
    }

    @Test
    void enrollConnectionFailureIsFailure() throws IOException {
        String unreachable = "http://localhost:" + unboundPort();

        FaceRecognitionClient.FaceEnrollResult result =
                client(snapshot(unreachable, "test-key", 0.80)).enroll(new byte[] {1, 2, 3}, "alice");

        assertFalse(result.success());
        assertNotNull(result.reason());
    }

    @Test
    void enrollNotConfiguredIsFailureWithoutNetworkCall() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/api/v1/recognition/faces", exchange -> {
            hits.incrementAndGet();
            sendJson(exchange, 200, "{\"image_id\":\"x\"}");
        });

        FaceRecognitionClient.FaceEnrollResult result =
                client(snapshot(null, null, 0.80)).enroll(new byte[] {1, 2, 3}, "alice");

        assertFalse(result.success());
        assertEquals(0, hits.get());
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
