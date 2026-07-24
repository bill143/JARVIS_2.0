package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.JarvisApi;
import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.memory.InMemoryStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end HTTP coverage for the four Phase 4 vision routes: {@code POST /vision/motion},
 * {@code POST /vision/enroll}, {@code GET /vision/status}, {@code GET /vision/visits}. Wires real
 * {@link VisionSettings}/{@link PeopleStore}/{@link PendingVisitorStore}/{@link MotionEventService}/
 * {@link UnknownVisitorEnrollmentService} collaborators (backed by in-memory stores and a
 * {@code @TempDir}) with a {@link FakeFaceRecognitionClient}, matching the hand-written fake
 * convention the rest of this app's vision tests use.
 */
class VisionEndpointsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private final HttpClient client = HttpClient.newHttpClient();
    private WebServer server;
    private String base;

    private ConnectorSettingsService connectors;
    private VisionSettings visionSettings;
    private PeopleStore peopleStore;
    private PendingVisitorStore pendingStore;
    private InMemoryRecordStore visitLogStore;
    private FakeFaceRecognitionClient faceClient;
    private MutableClock clock;
    private MotionEventService motionEvents;
    private UnknownVisitorEnrollmentService enrollment;

    @BeforeEach
    void setUp() {
        connectors = new ConnectorSettingsService(new InMemoryStore<>(), Map.<String, String>of()::get);
        visionSettings = new VisionSettings(connectors);
        peopleStore = new PeopleStore(tempDir.resolve("people.json"));
        pendingStore = new PendingVisitorStore(new InMemoryStore<>());
        visitLogStore = new InMemoryRecordStore();
        faceClient = new FakeFaceRecognitionClient();
        clock = new MutableClock(Instant.parse("2026-07-23T10:00:00Z"));
        connectors.set("vision.storage.root", tempDir.resolve("snapshots").toString());
        motionEvents = new MotionEventService(faceClient, visionSettings, peopleStore, pendingStore,
                new PresenceGreetingService(), visitLogStore,
                url -> {
                    throw new IOException("snapshotFetcher should not be used by these tests");
                }, clock, null);
        enrollment = new UnknownVisitorEnrollmentService(faceClient, peopleStore, pendingStore, clock, null);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    /** Starts a server with the full vision subsystem wired in (governance non-null throughout). */
    private void startWired() throws Exception {
        AppWiring.VisionServices visionServices = new AppWiring.VisionServices(
                visionSettings, motionEvents, enrollment, visitLogStore, faceClient);
        startWithGovernance(visionServices);
    }

    /** Starts a server with a real, non-null Governance whose visionServices field is itself null. */
    private void startWiredWithoutVisionServices() throws Exception {
        startWithGovernance(null);
    }

    private void startWithGovernance(AppWiring.VisionServices visionServices) throws Exception {
        AppWiring.Governance governance = new AppWiring.Governance(null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, visionServices);
        JarvisApi api = AppWiring.buildApi(null, "test-model", new InMemoryStore<>());
        server = WebServer.start(api, false, "test-model", 0, new HardwareMonitor(), null, false, null,
                new InMemoryStore<>(), peopleStore, null, governance);
        base = "http://localhost:" + server.port();
    }

    /** Starts a server the way the plain launcher does with no governance object at all. */
    private void startUnwired() throws Exception {
        JarvisApi api = AppWiring.buildApi(null, "test-model", new InMemoryStore<>());
        server = WebServer.start(api, false, "test-model", 0);
        base = "http://localhost:" + server.port();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return postWithHeader(path, json, null, null);
    }

    private HttpResponse<String> postWithHeader(String path, String json, String headerName,
            String headerValue) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json");
        if (headerName != null) {
            b.header(headerName, headerValue);
        }
        return client.send(b.POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String motionBody(String cameraId) {
        String b64 = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4});
        return "{\"cameraId\":\"" + cameraId + "\",\"timestamp\":\"2026-07-23T10:00:00Z\","
                + "\"imageBase64\":\"" + b64 + "\"}";
    }

    private void assertVisitLogEventually(int expectedAtLeast) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1_000L;
        while (System.currentTimeMillis() < deadline) {
            if (visitLogStore.list("vision-visits").size() >= expectedAtLeast) {
                return;
            }
            Thread.sleep(25);
        }
        assertEquals(expectedAtLeast, visitLogStore.list("vision-visits").size());
    }

    // ---- Inert Default Behavior: the whole vision HTTP surface is a no-op when unwired ---------

    @Test
    void wholeVisionSurfaceIsANoOpWhenNotWiredAtAll() throws Exception {
        startUnwired();

        assertEquals(503, post("/vision/motion", "{}").statusCode());
        assertEquals(503, post("/vision/enroll", "{}").statusCode());

        HttpResponse<String> status = get("/vision/status");
        assertEquals(200, status.statusCode());
        assertTrue(status.body().contains("\"available\":false"), status.body());

        HttpResponse<String> visits = get("/vision/visits");
        assertEquals(200, visits.statusCode());
        assertEquals("[]", visits.body());
    }

    @Test
    void wholeVisionSurfaceIsANoOpWhenGovernanceIsWiredButVisionServicesIsNull() throws Exception {
        startWiredWithoutVisionServices();

        assertEquals(503, post("/vision/motion", "{}").statusCode());
        assertEquals(503, post("/vision/enroll", "{}").statusCode());

        HttpResponse<String> status = get("/vision/status");
        assertEquals(200, status.statusCode());
        assertTrue(status.body().contains("\"available\":false"), status.body());

        HttpResponse<String> visits = get("/vision/visits");
        assertEquals(200, visits.statusCode());
        assertEquals("[]", visits.body());
    }

    // ---- /vision/motion ---------------------------------------------------------------------------

    @Test
    void motionWrongMethodIs405() throws Exception {
        startWired();
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder(URI.create(base + "/vision/motion")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, r.statusCode());
    }

    @Test
    void motionWithConfiguredSecretRejectsMissingOrWrongHeaderAndAcceptsCorrectOne() throws Exception {
        connectors.set("vision.motion.enabled", "true");
        connectors.set("vision.face.enabled", "false");
        connectors.set("vision.motion.webhookSecret", "topsecret-value");
        startWired();

        assertEquals(401, post("/vision/motion", motionBody("cam1")).statusCode());
        assertEquals(401, postWithHeader("/vision/motion", motionBody("cam1"),
                "X-Vision-Webhook-Secret", "wrong-value").statusCode());
        assertTrue(visitLogStore.list("vision-visits").isEmpty(),
                "a rejected webhook must never be processed");

        HttpResponse<String> ok = postWithHeader("/vision/motion", motionBody("cam1"),
                "X-Vision-Webhook-Secret", "topsecret-value");
        assertEquals(202, ok.statusCode());
        assertTrue(ok.body().contains("\"accepted\":true"), ok.body());

        // Background processing runs on a virtual thread after the 202 response; poll for it.
        assertVisitLogEventually(1);
    }

    @Test
    void motionWithNoSecretConfiguredAcceptsWithoutAnyHeader() throws Exception {
        connectors.set("vision.motion.enabled", "true");
        connectors.set("vision.face.enabled", "false");
        startWired();

        HttpResponse<String> r = post("/vision/motion", motionBody("cam1"));
        assertEquals(202, r.statusCode());
        assertTrue(r.body().contains("\"accepted\":true"), r.body());
        assertVisitLogEventually(1);
    }

    @Test
    void motionBadJsonIs400() throws Exception {
        connectors.set("vision.motion.enabled", "true");
        startWired();
        HttpResponse<String> r = post("/vision/motion", "not json");
        assertEquals(400, r.statusCode());
    }

    @Test
    void motionRunsFaceRecognitionInTheBackgroundAndPendingVisitorAppearsForAnUnknownFace()
            throws Exception {
        connectors.set("vision.motion.enabled", "true");
        connectors.set("vision.face.enabled", "true");
        faceClient.defaultMatch(FaceRecognitionClient.FaceMatchResult.noMatch());
        startWired();

        HttpResponse<String> r = post("/vision/motion", motionBody("cam1"));
        assertEquals(202, r.statusCode());
        assertVisitLogEventually(1);

        // The unknown-visitor path stores a pending visitor entry with a token; the visit-log
        // record itself carries the pendingToken field.
        String payload = visitLogStore.list("vision-visits").get(0).payload();
        JsonNode node = MAPPER.readTree(payload);
        assertTrue(node.has("pendingToken"), payload);
        assertFalse(node.path("pendingToken").asText("").isBlank(), payload);
    }

    // ---- /vision/enroll ---------------------------------------------------------------------------

    @Test
    void enrollWrongMethodIs405() throws Exception {
        startWired();
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder(URI.create(base + "/vision/enroll")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, r.statusCode());
    }

    @Test
    void enrollBadJsonIs400() throws Exception {
        startWired();
        assertEquals(400, post("/vision/enroll", "not json").statusCode());
    }

    @Test
    void enrollHappyPathReturns200WithSuccessTrue() throws Exception {
        Path snapshot = tempDir.resolve(UUID.randomUUID() + ".jpg");
        Files.write(snapshot, new byte[] {1, 2, 3});
        String token = pendingStore.create("cam1", snapshot.toString(), clock.get(), 300).token();
        faceClient.queueEnroll(FaceRecognitionClient.FaceEnrollResult.success("subject-new"));
        startWired();

        HttpResponse<String> r = post("/vision/enroll",
                "{\"pendingToken\":\"" + token + "\",\"name\":\"Grace Hopper\"}");

        assertEquals(200, r.statusCode());
        JsonNode node = MAPPER.readTree(r.body());
        assertTrue(node.path("success").asBoolean(false), r.body());
        assertEquals("Grace Hopper", node.path("personName").asText());
        assertFalse(node.path("personId").asText("").isBlank());
    }

    @Test
    void enrollWithBadOrExpiredTokenReturns200WithSuccessFalse() throws Exception {
        startWired();

        HttpResponse<String> r = post("/vision/enroll",
                "{\"pendingToken\":\"does-not-exist\",\"name\":\"Someone\"}");

        assertEquals(200, r.statusCode());
        JsonNode node = MAPPER.readTree(r.body());
        assertFalse(node.path("success").asBoolean(true), r.body());
        assertEquals("token-not-found-or-expired", node.path("reason").asText());
    }

    // ---- /vision/status ---------------------------------------------------------------------------

    @Test
    void statusReflectsConfiguredSnapshotAndNeverLeaksSecretsOrApiKeys() throws Exception {
        String secretValue = "shh-webhook-secret-9182";
        String apiKeyValue = "shh-api-key-7734";
        connectors.set("vision.motion.enabled", "true");
        connectors.set("vision.motion.cooldownSec", "45");
        connectors.set("vision.motion.webhookSecret", secretValue);
        connectors.set("vision.face.enabled", "true");
        connectors.set("vision.face.provider", "compreface");
        connectors.set("vision.face.baseUrl", "http://localhost:9999");
        connectors.set("vision.face.apiKey", apiKeyValue);
        connectors.set("vision.face.similarityThreshold", "0.9");
        connectors.set("vision.face.pendingTtlSec", "120");
        startWired();

        HttpResponse<String> r = get("/vision/status");
        assertEquals(200, r.statusCode());
        String body = r.body();

        assertTrue(body.contains("\"available\":true"), body);
        assertTrue(body.contains("\"motionEnabled\":true"), body);
        assertTrue(body.contains("\"motionCooldownSec\":45"), body);
        assertTrue(body.contains("\"faceEnabled\":true"), body);
        assertTrue(body.contains("\"faceProvider\":\"compreface\""), body);
        assertTrue(body.contains("\"faceSimilarityThreshold\":0.9"), body);
        assertTrue(body.contains("\"facePendingTtlSec\":120"), body);
        assertTrue(body.contains("\"faceConfigured\":true"), body);

        assertFalse(body.contains(secretValue), "webhook secret must never be echoed to the client");
        assertFalse(body.contains(apiKeyValue), "api key must never be echoed to the client");
        assertFalse(body.contains("webhookSecret"));
        assertFalse(body.contains("apiKey"));
    }

    @Test
    void statusDefaultsToDisabledWithoutAnyConfiguration() throws Exception {
        startWired();

        HttpResponse<String> r = get("/vision/status");
        assertEquals(200, r.statusCode());
        String body = r.body();
        assertTrue(body.contains("\"available\":true"), body);
        assertTrue(body.contains("\"motionEnabled\":false"), body);
        assertTrue(body.contains("\"faceEnabled\":false"), body);
        assertTrue(body.contains("\"faceConfigured\":false"), body);
    }

    // ---- /vision/visits ---------------------------------------------------------------------------

    @Test
    void visitsReturnsRecordsAfterMotionEventsAndRespectsLimit() throws Exception {
        connectors.set("vision.motion.enabled", "true");
        connectors.set("vision.face.enabled", "false");
        connectors.set("vision.motion.cooldownSec", "0");
        startWired();

        for (int i = 0; i < 3; i++) {
            HttpResponse<String> r = post("/vision/motion", motionBody("cam" + i));
            assertEquals(202, r.statusCode());
        }
        assertVisitLogEventually(3);

        HttpResponse<String> all = get("/vision/visits");
        assertEquals(200, all.statusCode());
        JsonNode allArr = MAPPER.readTree(all.body());
        assertTrue(allArr.isArray());
        assertEquals(3, allArr.size(), all.body());
        JsonNode first = allArr.get(0);
        assertTrue(first.has("seq"));
        assertTrue(first.has("at"));
        assertTrue(first.path("payload").isObject());
        assertTrue(first.path("payload").has("cameraId"));

        HttpResponse<String> limited = get("/vision/visits?limit=2");
        assertEquals(200, limited.statusCode());
        JsonNode limitedArr = MAPPER.readTree(limited.body());
        assertEquals(2, limitedArr.size(), limited.body());
    }

    private static final class MutableClock implements Supplier<Instant> {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant get() {
            return now;
        }
    }
}
