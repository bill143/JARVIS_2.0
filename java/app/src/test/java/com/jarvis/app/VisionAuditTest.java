package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEntry;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditQuery;
import com.jarvis.audit.RecordStoreAuditLog;
import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.memory.InMemoryStore;
import com.jarvis.memory.RecordStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 5 coverage: audit logging for the vision motion + face-recognition flow, plus the fuller
 * {@link PendingVisitorStore#pruneExpired} retention sweep. Uses a real
 * {@link RecordStoreAuditLog} backed by an {@link InMemoryRecordStore} (no hand-rolled fake) so
 * assertions exercise the real serialize/deserialize round-trip.
 *
 * <p>The privacy tests are the load-bearing ones here: every audit {@code detail} must be pure
 * metadata (camera id, timestamp, person/subject id, similarity, pending token, reason) and must
 * never carry raw image bytes or base64 image content.
 */
class VisionAuditTest {

    @TempDir
    Path tempDir;

    private ConnectorSettingsService connectors;
    private VisionSettings visionSettings;
    private PeopleStore peopleStore;
    private PendingVisitorStore pendingStore;
    private InMemoryRecordStore visitLogStore;
    private PresenceGreetingService greetingService;
    private FakeFaceRecognitionClient faceClient;
    private MutableClock clock;
    private AuditLog audit;

    @BeforeEach
    void setUp() {
        connectors = new ConnectorSettingsService(new InMemoryStore<>(), Map.<String, String>of()::get);
        visionSettings = new VisionSettings(connectors);
        peopleStore = new PeopleStore(tempDir.resolve("people.json"));
        pendingStore = new PendingVisitorStore(new InMemoryStore<>());
        visitLogStore = new InMemoryRecordStore();
        greetingService = new PresenceGreetingService();
        faceClient = new FakeFaceRecognitionClient();
        clock = new MutableClock(Instant.parse("2026-07-23T10:00:00Z"));
        audit = new RecordStoreAuditLog(new InMemoryRecordStore());
        connectors.set("vision.storage.root", tempDir.resolve("snapshots").toString());
    }

    private void enableMotionAndFace() {
        connectors.set("vision.motion.enabled", "true");
        connectors.set("vision.face.enabled", "true");
    }

    private MotionEventService motionService(RecordStore visitLog, AuditLog auditLog) {
        return new MotionEventService(faceClient, visionSettings, peopleStore, pendingStore,
                greetingService, visitLog, url -> {
                    throw new IOException("snapshotFetcher should not be used by these tests");
                }, clock, auditLog);
    }

    private static String base64Bytes() {
        return Base64.getEncoder().encodeToString(new byte[] {10, 20, 30, 40});
    }

    private static MotionEventService.MotionEventRequest req(String cameraId, String base64) {
        return new MotionEventService.MotionEventRequest(cameraId, "2026-07-23T10:00:00Z", base64, null);
    }

    private List<AuditEntry> allEntries() {
        return audit.query(AuditQuery.all());
    }

    // ---- motion accepted -----------------------------------------------------------------------

    @Test
    void motionAcceptedRecordsExactlyOneVisionMotionReceivedEvent() {
        connectors.set("vision.motion.enabled", "true");
        connectors.set("vision.face.enabled", "false");
        MotionEventService svc = motionService(visitLogStore, audit);

        MotionEventService.MotionEventResult result = svc.handle(req("cam1", base64Bytes()));
        assertTrue(result.accepted());

        List<AuditEntry> received = allEntries().stream()
                .filter(e -> "VISION_MOTION_RECEIVED".equals(e.event().action()))
                .toList();
        assertEquals(1, received.size());
        AuditEntry entry = received.get(0);
        assertEquals(AuditCategory.SYSTEM, entry.event().category());
        assertEquals(AuditOutcome.SUCCESS, entry.event().outcome());
        assertTrue(entry.event().detail().contains("camera=cam1"), entry.event().detail());
        assertTrue(entry.event().detail().contains("timestamp=2026-07-23T10:00:00Z"),
                entry.event().detail());
    }

    // ---- motion disabled: fully silent ----------------------------------------------------------

    @Test
    void motionDisabledRecordsZeroAuditEvents() {
        // vision.motion.enabled defaults to false; nothing set.
        MotionEventService svc = motionService(visitLogStore, audit);

        MotionEventService.MotionEventResult result = svc.handle(req("cam1", base64Bytes()));

        assertFalse(result.accepted());
        assertTrue(allEntries().isEmpty());
    }

    // ---- cooldown-skipped event adds nothing ------------------------------------------------------

    @Test
    void cooldownSkippedEventDoesNotAddASecondMotionReceivedEvent() {
        connectors.set("vision.motion.enabled", "true");
        connectors.set("vision.motion.cooldownSec", "20");
        connectors.set("vision.face.enabled", "false");
        MotionEventService svc = motionService(visitLogStore, audit);

        MotionEventService.MotionEventResult first = svc.handle(req("cam1", base64Bytes()));
        assertTrue(first.accepted());

        clock.advance(5);
        MotionEventService.MotionEventResult second = svc.handle(req("cam1", base64Bytes()));
        assertFalse(second.accepted());
        assertEquals("cooldown", second.reason());

        long receivedCount = allEntries().stream()
                .filter(e -> "VISION_MOTION_RECEIVED".equals(e.event().action()))
                .count();
        assertEquals(1, receivedCount);
    }

    // ---- matched known person ---------------------------------------------------------------------

    @Test
    void matchedKnownPersonRecordsFaceRecognizedAlongsideMotionReceived() {
        enableMotionAndFace();
        String personId = peopleStore.addWithFaceSubject("Richard", "sub-1", "Rich");
        faceClient.queueMatch(FaceRecognitionClient.FaceMatchResult.matched("sub-1", 0.95));
        MotionEventService svc = motionService(visitLogStore, audit);

        MotionEventService.MotionEventResult result = svc.handle(req("cam1", base64Bytes()));
        assertTrue(result.accepted());
        assertTrue(result.recognized());

        List<AuditEntry> events = allEntries();
        assertTrue(events.stream().anyMatch(e -> "VISION_MOTION_RECEIVED".equals(e.event().action())));
        AuditEntry recognized = events.stream()
                .filter(e -> "VISION_FACE_RECOGNIZED".equals(e.event().action()))
                .findFirst().orElseThrow();
        assertEquals(AuditCategory.EXTERNAL_API, recognized.event().category());
        assertEquals(AuditOutcome.SUCCESS, recognized.event().outcome());
        assertTrue(recognized.event().detail().contains("personId=" + personId),
                recognized.event().detail());
        assertTrue(recognized.event().detail().contains("similarity=0.95"), recognized.event().detail());
    }

    // ---- unknown visitor: clean NO_MATCH ------------------------------------------------------------

    @Test
    void noMatchRecordsFaceUnknownWithSuccessOutcome() {
        enableMotionAndFace();
        faceClient.queueMatch(FaceRecognitionClient.FaceMatchResult.noMatch());
        MotionEventService svc = motionService(visitLogStore, audit);

        MotionEventService.MotionEventResult result = svc.handle(req("cam1", base64Bytes()));
        assertTrue(result.accepted());
        assertNotNull(result.pendingToken());

        AuditEntry unknown = allEntries().stream()
                .filter(e -> "VISION_FACE_UNKNOWN".equals(e.event().action()))
                .findFirst().orElseThrow();
        assertEquals(AuditOutcome.SUCCESS, unknown.event().outcome());
        assertTrue(unknown.event().detail().contains("pendingToken=" + result.pendingToken()),
                unknown.event().detail());
    }

    // ---- recognizer ERROR degrades to pending visitor -----------------------------------------------

    @Test
    void recognizerErrorRecordsFaceUnknownWithFailureOutcome() {
        enableMotionAndFace();
        faceClient.queueMatch(FaceRecognitionClient.FaceMatchResult.error("provider down"));
        MotionEventService svc = motionService(visitLogStore, audit);

        MotionEventService.MotionEventResult result = svc.handle(req("cam1", base64Bytes()));
        assertTrue(result.accepted());

        AuditEntry unknown = allEntries().stream()
                .filter(e -> "VISION_FACE_UNKNOWN".equals(e.event().action()))
                .findFirst().orElseThrow();
        assertEquals(AuditOutcome.FAILURE, unknown.event().outcome());
        assertTrue(unknown.event().detail().contains("providerError=provider down"),
                unknown.event().detail());
    }

    // ---- privacy: motion-event audit details never carry raw image content --------------------------

    @Test
    void motionEventAuditDetailsNeverContainRawImageContent() {
        enableMotionAndFace();
        faceClient.queueMatch(FaceRecognitionClient.FaceMatchResult.noMatch());
        MotionEventService svc = motionService(visitLogStore, audit);

        byte[] raw = "TOTALLY-FAKE-IMAGE-BYTES-MARKER".getBytes();
        String b64 = Base64.getEncoder().encodeToString(raw);
        String markerAscii = new String(raw);

        svc.handle(req("cam1", b64));

        assertFalse(allEntries().isEmpty()); // sanity: something was actually recorded
        for (AuditEntry entry : allEntries()) {
            assertFalse(entry.event().detail().contains(b64), entry.event().detail());
            assertFalse(entry.event().detail().contains(markerAscii), entry.event().detail());
        }
    }

    // ---- enrollment success ----------------------------------------------------------------------

    @Test
    void enrollmentSuccessRecordsCompletedEventAndNeverLeaksImageBytes() throws IOException {
        byte[] raw = "TOTALLY-FAKE-IMAGE-BYTES-MARKER".getBytes();
        Path snapshot = tempDir.resolve(UUID.randomUUID() + ".jpg");
        Files.write(snapshot, raw);
        String token = pendingStore.create("cam1", snapshot.toString(), clock.get(), 300).token();
        faceClient.queueEnroll(FaceRecognitionClient.FaceEnrollResult.success("subject-new"));
        UnknownVisitorEnrollmentService service =
                new UnknownVisitorEnrollmentService(faceClient, peopleStore, pendingStore, clock, audit);

        UnknownVisitorEnrollmentService.EnrollmentResult result = service.complete(token, "Grace Hopper");
        assertTrue(result.success());

        AuditEntry completed = allEntries().stream()
                .filter(e -> "VISION_ENROLLMENT_COMPLETED".equals(e.event().action()))
                .findFirst().orElseThrow();
        assertEquals(AuditCategory.EXTERNAL_API, completed.event().category());
        assertEquals(AuditOutcome.SUCCESS, completed.event().outcome());
        assertTrue(completed.event().detail().contains("personId=" + result.personId()),
                completed.event().detail());
        assertTrue(completed.event().detail().contains("personName=Grace Hopper"),
                completed.event().detail());

        String markerAscii = new String(raw);
        String b64 = Base64.getEncoder().encodeToString(raw);
        for (AuditEntry entry : allEntries()) {
            assertFalse(entry.event().detail().contains(markerAscii), entry.event().detail());
            assertFalse(entry.event().detail().contains(b64), entry.event().detail());
        }
    }

    // ---- enrollment failure ----------------------------------------------------------------------

    @Test
    void enrollmentFailureWithBadTokenRecordsFailedEvent() {
        UnknownVisitorEnrollmentService service =
                new UnknownVisitorEnrollmentService(faceClient, peopleStore, pendingStore, clock, audit);

        UnknownVisitorEnrollmentService.EnrollmentResult result =
                service.complete("does-not-exist", "Someone");
        assertFalse(result.success());

        AuditEntry failed = allEntries().stream()
                .filter(e -> "VISION_ENROLLMENT_FAILED".equals(e.event().action()))
                .findFirst().orElseThrow();
        assertEquals(AuditOutcome.FAILURE, failed.event().outcome());
        assertTrue(failed.event().detail().contains("reason=token-not-found-or-expired"),
                failed.event().detail());
    }

    // ---- PendingVisitorStore.pruneExpired ------------------------------------------------------------

    @Test
    void pruneExpiredDeletesOnlyExpiredEntriesAndTheirSnapshotFiles() throws IOException {
        PendingVisitorStore store = new PendingVisitorStore(new InMemoryStore<>());
        Instant now = Instant.parse("2026-07-23T10:00:00Z");

        Path expiredSnapshot = tempDir.resolve("expired.jpg");
        Files.write(expiredSnapshot, new byte[] {1, 2, 3});
        PendingVisitorStore.PendingVisitor expired =
                store.create("cam1", expiredSnapshot.toString(), now.minusSeconds(600), 60); // expired 8m ago

        Path freshSnapshot = tempDir.resolve("fresh.jpg");
        Files.write(freshSnapshot, new byte[] {4, 5, 6});
        PendingVisitorStore.PendingVisitor fresh =
                store.create("cam1", freshSnapshot.toString(), now, 300); // expires in 5m

        int pruned = store.pruneExpired(now);

        assertEquals(1, pruned);
        assertFalse(Files.exists(expiredSnapshot));
        assertTrue(store.get(expired.token(), now).isEmpty());

        assertTrue(Files.exists(freshSnapshot));
        assertTrue(store.get(fresh.token(), now).isPresent());
    }

    // ---- nullable audit collaborator --------------------------------------------------------------

    @Test
    void nullAuditCollaboratorNeverThrows() {
        enableMotionAndFace();
        faceClient.queueMatch(FaceRecognitionClient.FaceMatchResult.noMatch());
        MotionEventService svc = motionService(visitLogStore, null);

        assertDoesNotThrow(() -> svc.handle(req("cam1", base64Bytes())));
    }

    private static final class MutableClock implements Supplier<Instant> {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public Instant get() {
            return now;
        }
    }
}
