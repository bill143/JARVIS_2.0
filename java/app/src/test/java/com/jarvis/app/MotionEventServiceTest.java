package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.memory.InMemoryStore;
import com.jarvis.memory.RecordStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MotionEventServiceTest {

    @TempDir
    Path tempDir;

    private ConnectorSettingsService connectors;
    private VisionSettings visionSettings;
    private PeopleStore peopleStore;
    private InMemoryStore<String> pendingBackingStore;
    private PendingVisitorStore pendingStore;
    private InMemoryRecordStore visitLogStore;
    private PresenceGreetingService greetingService;
    private FakeFaceRecognitionClient faceClient;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        connectors = new ConnectorSettingsService(new InMemoryStore<>(), Map.<String, String>of()::get);
        visionSettings = new VisionSettings(connectors);
        peopleStore = new PeopleStore(tempDir.resolve("people.json"));
        pendingBackingStore = new InMemoryStore<>();
        pendingStore = new PendingVisitorStore(pendingBackingStore);
        visitLogStore = new InMemoryRecordStore();
        greetingService = new PresenceGreetingService();
        faceClient = new FakeFaceRecognitionClient();
        clock = new MutableClock(Instant.parse("2026-07-23T10:00:00Z"));
        connectors.set("vision.storage.root", tempDir.resolve("snapshots").toString());
    }

    private void enableMotionAndFace() {
        connectors.set("vision.motion.enabled", "true");
        connectors.set("vision.face.enabled", "true");
    }

    private MotionEventService service(RecordStore visitLog, MotionEventService.SnapshotFetcher fetcher) {
        return new MotionEventService(faceClient, visionSettings, peopleStore, pendingStore,
                greetingService, visitLog, fetcher, clock, null);
    }

    /** A fetcher that fails the test if the base64 path should have been used instead. */
    private static MotionEventService.SnapshotFetcher failFetcher() {
        return url -> {
            throw new IOException("snapshotFetcher should not have been called for url " + url);
        };
    }

    private static String base64Bytes() {
        return Base64.getEncoder().encodeToString(new byte[] {10, 20, 30, 40});
    }

    private static MotionEventService.MotionEventRequest req(String cameraId, String base64) {
        return new MotionEventService.MotionEventRequest(cameraId, "2026-07-23T10:00:00Z", base64, null);
    }

    // ---- Inert Default Behavior: the single most important invariant -------------------------

    @Test
    void disabledMotionIsFullyInert() {
        // vision.motion.enabled defaults to false; nothing set.
        MotionEventService svc = service(visitLogStore, failFetcher());

        MotionEventService.MotionEventResult result = svc.handle(req("cam1", base64Bytes()));

        assertFalse(result.accepted());
        assertEquals("motion-disabled", result.reason());
        assertNull(result.greeting());
        assertFalse(result.recognized());
        assertNull(result.personId());
        assertNull(result.personName());
        assertNull(result.pendingToken());

        assertTrue(pendingBackingStore.query("vision-pending").isEmpty());
        assertTrue(visitLogStore.list("vision-visits").isEmpty());
        assertTrue(faceClient.recognizeCalls().isEmpty());

        // The cooldown map must not have been touched either: enabling motion afterward and
        // replaying the exact same instant must NOT be mistaken for a cooldown skip.
        connectors.set("vision.motion.enabled", "true");
        connectors.set("vision.face.enabled", "false");
        MotionEventService.MotionEventResult second = svc.handle(req("cam1", base64Bytes()));
        assertTrue(second.accepted());
        assertNotEquals("cooldown", second.reason());
    }

    // ---- cooldown -------------------------------------------------------------------------------

    @Test
    void cooldownSkipsSubsequentEventsWithinTheWindow() {
        connectors.set("vision.motion.enabled", "true");
        connectors.set("vision.motion.cooldownSec", "20");
        connectors.set("vision.face.enabled", "false");
        MotionEventService svc = service(visitLogStore, failFetcher());

        MotionEventService.MotionEventResult first = svc.handle(req("cam1", base64Bytes()));
        assertTrue(first.accepted());

        clock.advance(5);
        MotionEventService.MotionEventResult second = svc.handle(req("cam1", base64Bytes()));
        assertFalse(second.accepted());
        assertEquals("cooldown", second.reason());

        clock.advance(20); // now 25s after the first event, past the 20s cooldown
        MotionEventService.MotionEventResult third = svc.handle(req("cam1", base64Bytes()));
        assertTrue(third.accepted());
    }

    // ---- image extraction -----------------------------------------------------------------------

    @Test
    void base64ImagePathDecodesBytesAndPassesThemToTheFaceClient() {
        enableMotionAndFace();
        faceClient.queueMatch(FaceRecognitionClient.FaceMatchResult.noMatch());
        byte[] raw = {1, 2, 3, 4};
        String b64 = Base64.getEncoder().encodeToString(raw);
        MotionEventService svc = service(null, failFetcher());

        svc.handle(new MotionEventService.MotionEventRequest("cam1", "t", b64, null));

        assertEquals(1, faceClient.recognizeCalls().size());
        assertArrayEquals(raw, faceClient.recognizeCalls().get(0));
    }

    @Test
    void snapshotUrlPathFetchesBytesAndPassesThemToTheFaceClient() {
        enableMotionAndFace();
        faceClient.queueMatch(FaceRecognitionClient.FaceMatchResult.noMatch());
        byte[] raw = {5, 6, 7};
        MotionEventService.SnapshotFetcher fetcher = url -> {
            assertEquals("http://cam/snap.jpg", url);
            return raw;
        };
        MotionEventService svc = service(null, fetcher);

        svc.handle(new MotionEventService.MotionEventRequest("cam1", "t", null, "http://cam/snap.jpg"));

        assertEquals(1, faceClient.recognizeCalls().size());
        assertArrayEquals(raw, faceClient.recognizeCalls().get(0));
    }

    @Test
    void noImageIsRejectedWithoutCallingTheFaceClient() {
        enableMotionAndFace();
        MotionEventService svc = service(visitLogStore, failFetcher());

        MotionEventService.MotionEventResult result =
                svc.handle(new MotionEventService.MotionEventRequest("cam1", "t", null, null));

        assertFalse(result.accepted());
        assertEquals("no-image", result.reason());
        assertTrue(faceClient.recognizeCalls().isEmpty());
    }

    @Test
    void malformedBase64IsRejectedWithoutThrowing() {
        enableMotionAndFace();
        MotionEventService svc = service(visitLogStore, failFetcher());

        MotionEventService.MotionEventResult result = assertDoesNotThrow(() -> svc.handle(
                new MotionEventService.MotionEventRequest("cam1", "t", "not-valid-base64!!", null)));

        assertFalse(result.accepted());
        assertEquals("image-extract-failed", result.reason());
    }

    @Test
    void snapshotFetchIoExceptionIsRejectedWithoutThrowing() {
        enableMotionAndFace();
        MotionEventService.SnapshotFetcher fetcher = url -> {
            throw new IOException("camera unreachable");
        };
        MotionEventService svc = service(visitLogStore, fetcher);

        MotionEventService.MotionEventResult result = assertDoesNotThrow(() -> svc.handle(
                new MotionEventService.MotionEventRequest("cam1", "t", null, "http://cam/snap.jpg")));

        assertFalse(result.accepted());
        assertEquals("image-extract-failed", result.reason());
    }

    // ---- face-disabled: motion-only ---------------------------------------------------------------

    @Test
    void faceDisabledSkipsRecognitionEntirelyAndStillLogsTheMotionEvent() {
        connectors.set("vision.motion.enabled", "true");
        connectors.set("vision.face.enabled", "false");
        MotionEventService svc = service(visitLogStore, failFetcher());

        MotionEventService.MotionEventResult result = svc.handle(req("cam1", base64Bytes()));

        assertTrue(result.accepted());
        assertEquals("motion-only", result.reason());
        assertFalse(result.recognized());
        assertNull(result.greeting());
        assertNull(result.pendingToken());
        assertTrue(faceClient.recognizeCalls().isEmpty());
        assertEquals(1, visitLogStore.list("vision-visits").size());
    }

    // ---- matched known person --------------------------------------------------------------------

    @Test
    void matchedKnownPersonUpdatesLastSeenAndGreetsByName() {
        enableMotionAndFace();
        String personId = peopleStore.addWithFaceSubject("Richard", "sub-1", "Rich");
        faceClient.queueMatch(FaceRecognitionClient.FaceMatchResult.matched("sub-1", 0.95));
        MotionEventService svc = service(visitLogStore, failFetcher());

        MotionEventService.MotionEventResult result = svc.handle(req("cam1", base64Bytes()));

        assertTrue(result.accepted());
        assertTrue(result.recognized());
        assertEquals(personId, result.personId());
        assertEquals("Richard", result.personName());
        assertEquals("Hi Rich, how are you?", result.greeting());
        assertNull(result.pendingToken());

        PeopleStore.Person updated = peopleStore.all().stream()
                .filter(p -> p.id().equals(personId)).findFirst().orElseThrow();
        assertEquals(clock.get().toString(), updated.lastSeenAt());

        assertEquals(1, visitLogStore.list("vision-visits").size());
    }

    @Test
    void matchedSubjectWithNoLocalOwnerFallsBackToUnknownVisitor() {
        enableMotionAndFace();
        // Subject "ghost" matched by the provider, but no PeopleStore person owns it (data drift).
        faceClient.queueMatch(FaceRecognitionClient.FaceMatchResult.matched("ghost", 0.9));
        MotionEventService svc = service(visitLogStore, failFetcher());

        MotionEventService.MotionEventResult result = svc.handle(req("cam1", base64Bytes()));

        assertTrue(result.accepted());
        assertFalse(result.recognized());
        assertNotNull(result.pendingToken());
        assertEquals("Hi, my name is Jarvis, what is your name?", result.greeting());
    }

    // ---- no-match: unknown visitor -----------------------------------------------------------------

    @Test
    void noMatchCreatesPendingVisitorAndWritesSnapshotFile() throws IOException {
        enableMotionAndFace();
        connectors.set("vision.face.pendingTtlSec", "300");
        faceClient.queueMatch(FaceRecognitionClient.FaceMatchResult.noMatch());
        MotionEventService svc = service(visitLogStore, failFetcher());

        byte[] raw = Base64.getDecoder().decode(base64Bytes());
        MotionEventService.MotionEventResult result = svc.handle(req("cam1", base64Bytes()));

        assertTrue(result.accepted());
        assertFalse(result.recognized());
        assertNotNull(result.pendingToken());
        assertEquals("Hi, my name is Jarvis, what is your name?", result.greeting());

        Optional<PendingVisitorStore.PendingVisitor> pending =
                pendingStore.get(result.pendingToken(), clock.get());
        assertTrue(pending.isPresent());
        assertEquals("cam1", pending.get().cameraId());
        assertEquals(clock.get().plusSeconds(300).toString(), pending.get().expiresAt());

        Path snapshotFile = Path.of(pending.get().snapshotPath());
        assertTrue(Files.exists(snapshotFile));
        assertArrayEquals(raw, Files.readAllBytes(snapshotFile));

        assertEquals(1, visitLogStore.list("vision-visits").size());
    }

    // ---- provider ERROR degrades gracefully -----------------------------------------------------

    @Test
    void recognitionErrorDegradesToPendingVisitorWithoutThrowing() {
        enableMotionAndFace();
        faceClient.queueMatch(FaceRecognitionClient.FaceMatchResult.error("provider down"));
        MotionEventService svc = service(visitLogStore, failFetcher());

        MotionEventService.MotionEventResult result =
                assertDoesNotThrow(() -> svc.handle(req("cam1", base64Bytes())));

        assertTrue(result.accepted());
        assertEquals("face-recognition-error", result.reason());
        assertFalse(result.recognized());
        assertNotNull(result.pendingToken());
        assertEquals("Hi, my name is Jarvis, what is your name?", result.greeting());
    }

    // ---- visit-log: optional collaborator -------------------------------------------------------

    @Test
    void nullVisitLogNeverThrows() {
        enableMotionAndFace();
        faceClient.queueMatch(FaceRecognitionClient.FaceMatchResult.noMatch());
        MotionEventService svc = service(null, failFetcher());

        assertDoesNotThrow(() -> svc.handle(req("cam1", base64Bytes())));
    }

    // ---- test clock -------------------------------------------------------------------------------

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
