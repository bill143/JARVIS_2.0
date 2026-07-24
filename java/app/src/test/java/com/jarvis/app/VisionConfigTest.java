package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.memory.InMemoryStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VisionConfigTest {

    private static VisionSettings settings(Map<String, String> env) {
        return new VisionSettings(new ConnectorSettingsService(new InMemoryStore<>(), env::get));
    }

    // ---- defaults --------------------------------------------------------------------------

    @Test
    void defaultsAreInertWhenNothingIsConfigured() {
        VisionSettings.Snapshot s = settings(Map.of()).snapshot();

        assertFalse(s.motion().enabled());
        assertNull(s.motion().webhookSecret());
        assertEquals(20, s.motion().cooldownSec());

        assertFalse(s.face().enabled());
        assertEquals("compreface", s.face().provider());
        assertNull(s.face().baseUrl());
        assertNull(s.face().apiKey());
        assertEquals(0.80, s.face().similarityThreshold(), 0.0001);
        assertEquals(300, s.face().pendingTtlSec());

        assertEquals(System.getProperty("user.home") + "/.jarvis/vision", s.storageRoot());
    }

    // ---- environment variable fallback ------------------------------------------------------

    @Test
    void environmentVariablesAreHonoredForMotionFields() {
        VisionSettings.Snapshot s = settings(Map.of(
                "JARVIS_VISION_MOTION_ENABLED", "true",
                "JARVIS_VISION_MOTION_WEBHOOK_SECRET", "shh-secret",
                "JARVIS_VISION_MOTION_COOLDOWN_SEC", "45")).snapshot();

        assertTrue(s.motion().enabled());
        assertEquals("shh-secret", s.motion().webhookSecret());
        assertEquals(45, s.motion().cooldownSec());
    }

    @Test
    void environmentVariablesAreHonoredForFaceFields() {
        VisionSettings.Snapshot s = settings(Map.of(
                "JARVIS_FACE_ENABLED", "true",
                "JARVIS_FACE_PROVIDER", "custom-provider",
                "JARVIS_FACE_BASE_URL", "http://127.0.0.1:8000",
                "JARVIS_FACE_API_KEY", "api-key-123",
                "JARVIS_FACE_SIMILARITY_THRESHOLD", "0.92",
                "JARVIS_FACE_PENDING_TTL_SEC", "120")).snapshot();

        assertTrue(s.face().enabled());
        assertEquals("custom-provider", s.face().provider());
        assertEquals("http://127.0.0.1:8000", s.face().baseUrl());
        assertEquals("api-key-123", s.face().apiKey());
        assertEquals(0.92, s.face().similarityThreshold(), 0.0001);
        assertEquals(120, s.face().pendingTtlSec());
    }

    @Test
    void environmentVariableIsHonoredForStorageRoot() {
        VisionSettings.Snapshot s = settings(Map.of("JARVIS_VISION_STORAGE_ROOT", "/data/vision")).snapshot();
        assertEquals("/data/vision", s.storageRoot());
    }

    @Test
    void invalidNumericAndBooleanValuesFallBackToDefaults() {
        VisionSettings.Snapshot s = settings(Map.of(
                "JARVIS_VISION_MOTION_COOLDOWN_SEC", "not-a-number",
                "JARVIS_FACE_SIMILARITY_THRESHOLD", "not-a-double",
                "JARVIS_FACE_PENDING_TTL_SEC", "-5")).snapshot();

        assertEquals(VisionSettings.DEFAULT_MOTION_COOLDOWN_SEC, s.motion().cooldownSec());
        assertEquals(VisionSettings.DEFAULT_FACE_SIMILARITY_THRESHOLD, s.face().similarityThreshold(), 0.0001);
        assertEquals(VisionSettings.DEFAULT_FACE_PENDING_TTL_SEC, s.face().pendingTtlSec());
    }

    // ---- saved value precedence ---------------------------------------------------------------

    @Test
    void savedConnectorValueOverridesTheEnvironmentForMotionEnabled() {
        ConnectorSettingsService connectors = new ConnectorSettingsService(new InMemoryStore<>(),
                Map.of("JARVIS_VISION_MOTION_ENABLED", "false")::get);
        connectors.set("vision.motion.enabled", "true");
        assertTrue(new VisionSettings(connectors).snapshot().motion().enabled());
    }

    @Test
    void savedConnectorValueOverridesTheEnvironmentForFaceApiKey() {
        ConnectorSettingsService connectors = new ConnectorSettingsService(new InMemoryStore<>(),
                Map.of("JARVIS_FACE_API_KEY", "from-env")::get);
        connectors.set("vision.face.apiKey", "from-ui");
        assertEquals("from-ui", new VisionSettings(connectors).snapshot().face().apiKey());
    }

    @Test
    void savedConnectorValueOverridesTheEnvironmentForStorageRoot() {
        ConnectorSettingsService connectors = new ConnectorSettingsService(new InMemoryStore<>(),
                Map.of("JARVIS_VISION_STORAGE_ROOT", "/env/vision")::get);
        connectors.set("vision.storage.root", "/saved/vision");
        assertEquals("/saved/vision", new VisionSettings(connectors).snapshot().storageRoot());
    }

    // ---- CONNECTORS catalog: secrecy flags -----------------------------------------------------

    @Test
    void secretFieldsAreMarkedSecretAndOthersAreNot() {
        List<ConnectorSettingsService.FieldSpec> fields = ConnectorSettingsService.CONNECTORS.get("vision");
        assertEquals(9, fields.size());

        for (ConnectorSettingsService.FieldSpec f : fields) {
            boolean expectedSecret = f.key().equals("vision.motion.webhookSecret")
                    || f.key().equals("vision.face.apiKey");
            assertEquals(expectedSecret, f.secret(), "unexpected secrecy for " + f.key());
        }
    }

    @Test
    void catalogFieldsAppearInDocumentedOrderWithExactKeysAndEnvNames() {
        List<ConnectorSettingsService.FieldSpec> fields = ConnectorSettingsService.CONNECTORS.get("vision");
        List<String> keys = fields.stream().map(ConnectorSettingsService.FieldSpec::key).toList();
        assertEquals(List.of(
                "vision.motion.enabled",
                "vision.motion.webhookSecret",
                "vision.motion.cooldownSec",
                "vision.face.enabled",
                "vision.face.provider",
                "vision.face.baseUrl",
                "vision.face.apiKey",
                "vision.face.similarityThreshold",
                "vision.face.pendingTtlSec"), keys);
    }

    // ---- FakeFaceRecognitionClient sanity ------------------------------------------------------

    @Test
    void fakeReturnsQueuedMatchResultThenFallsBackToDefault() {
        FakeFaceRecognitionClient fake = new FakeFaceRecognitionClient();
        FaceRecognitionClient.FaceMatchResult matched =
                FaceRecognitionClient.FaceMatchResult.matched("subject-42", 0.95);
        fake.queueMatch(matched);

        byte[] frame1 = {1, 2, 3};
        byte[] frame2 = {4, 5, 6};
        FaceRecognitionClient.FaceMatchResult first = fake.recognize(frame1);
        FaceRecognitionClient.FaceMatchResult second = fake.recognize(frame2);

        assertEquals(matched, first);
        assertEquals(FaceRecognitionClient.FaceMatchResult.Status.MATCHED, first.status());
        assertEquals("subject-42", first.subjectId());
        assertEquals(0.95, first.similarity(), 0.0001);

        // Queue drained: falls back to the default (no-match) rather than throwing.
        assertEquals(FaceRecognitionClient.FaceMatchResult.Status.NO_MATCH, second.status());

        assertEquals(2, fake.recognizeCalls().size());
        assertArrayEquals(frame1, fake.recognizeCalls().get(0));
        assertArrayEquals(frame2, fake.recognizeCalls().get(1));
    }

    @Test
    void fakeReturnsQueuedEnrollResultsAndRecordsPersonNames() {
        FakeFaceRecognitionClient fake = new FakeFaceRecognitionClient();
        fake.queueEnroll(FaceRecognitionClient.FaceEnrollResult.success("subject-7"));
        fake.queueEnroll(FaceRecognitionClient.FaceEnrollResult.failure("blurry image"));

        FaceRecognitionClient.FaceEnrollResult ok = fake.enroll(new byte[] {9}, "Alice");
        FaceRecognitionClient.FaceEnrollResult bad = fake.enroll(new byte[] {8}, "Bob");

        assertTrue(ok.success());
        assertEquals("subject-7", ok.subjectId());

        assertFalse(bad.success());
        assertEquals("blurry image", bad.reason());

        assertEquals(List.of("Alice", "Bob"), fake.enrollCalls());
    }

    @Test
    void fakeDefaultEnrollIsFailureWhenNothingQueued() {
        FakeFaceRecognitionClient fake = new FakeFaceRecognitionClient();
        FaceRecognitionClient.FaceEnrollResult result = fake.enroll(new byte[] {1}, "Carol");
        assertFalse(result.success());
    }
}
