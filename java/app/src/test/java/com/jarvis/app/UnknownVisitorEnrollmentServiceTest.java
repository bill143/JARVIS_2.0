package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.memory.InMemoryStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnknownVisitorEnrollmentServiceTest {

    @TempDir
    Path tempDir;

    private PeopleStore peopleStore;
    private PendingVisitorStore pendingStore;
    private FakeFaceRecognitionClient faceClient;
    private MutableClock clock;
    private UnknownVisitorEnrollmentService service;

    @BeforeEach
    void setUp() {
        peopleStore = new PeopleStore(tempDir.resolve("people.json"));
        pendingStore = new PendingVisitorStore(new InMemoryStore<>());
        faceClient = new FakeFaceRecognitionClient();
        clock = new MutableClock(Instant.parse("2026-07-23T10:00:00Z"));
        service = new UnknownVisitorEnrollmentService(faceClient, peopleStore, pendingStore, clock, null);
    }

    private String pendingTokenWithSnapshot(byte[] bytes, int ttlSec) throws IOException {
        Path snapshot = tempDir.resolve(UUID.randomUUID() + ".jpg");
        Files.write(snapshot, bytes);
        return pendingStore.create("cam1", snapshot.toString(), clock.get(), ttlSec).token();
    }

    // ---- happy path ---------------------------------------------------------------------------

    @Test
    void completingEnrollmentForANewNameCreatesANewPerson() throws IOException {
        String token = pendingTokenWithSnapshot(new byte[] {1, 2, 3}, 300);
        faceClient.queueEnroll(FaceRecognitionClient.FaceEnrollResult.success("subject-new"));

        UnknownVisitorEnrollmentService.EnrollmentResult result = service.complete(token, "Grace Hopper");

        assertTrue(result.success());
        assertNull(result.reason());
        assertEquals("Grace Hopper", result.personName());

        PeopleStore.Person created = peopleStore.all().stream()
                .filter(p -> p.id().equals(result.personId())).findFirst().orElseThrow();
        assertEquals("Grace Hopper", created.name());
        assertTrue(created.faceSubjects().contains("subject-new"));
        assertEquals("Grace Hopper", created.greetingName());
        assertEquals(clock.get().toString(), created.lastSeenAt());

        // Token is consumed: a second completion attempt no longer finds it.
        assertTrue(pendingStore.get(token, clock.get()).isEmpty());
    }

    @Test
    void completingEnrollmentForAnExistingNameAttachesTheSubjectToThatPerson() throws IOException {
        String existingId = peopleStore.add("Ann Lee", "sister", "ann@x.com", "", "", "", "");
        String token = pendingTokenWithSnapshot(new byte[] {4, 5, 6}, 300);
        faceClient.queueEnroll(FaceRecognitionClient.FaceEnrollResult.success("subject-existing"));

        UnknownVisitorEnrollmentService.EnrollmentResult result = service.complete(token, "ann lee");

        assertTrue(result.success());
        assertEquals(existingId, result.personId());

        PeopleStore.Person updated = peopleStore.all().stream()
                .filter(p -> p.id().equals(existingId)).findFirst().orElseThrow();
        assertTrue(updated.faceSubjects().contains("subject-existing"));
        // Pre-existing contact info is untouched.
        assertEquals("ann@x.com", updated.email());
        assertEquals(1, peopleStore.all().size()); // no duplicate person created
    }

    // ---- failure modes ------------------------------------------------------------------------

    @Test
    void blankNameFailsWithoutTouchingThePendingStore() throws IOException {
        String token = pendingTokenWithSnapshot(new byte[] {1}, 300);

        UnknownVisitorEnrollmentService.EnrollmentResult result = service.complete(token, "  ");

        assertFalse(result.success());
        assertEquals("name-required", result.reason());
        assertTrue(pendingStore.get(token, clock.get()).isPresent()); // token still usable
    }

    @Test
    void missingTokenFails() {
        UnknownVisitorEnrollmentService.EnrollmentResult result =
                service.complete("does-not-exist", "Someone");

        assertFalse(result.success());
        assertEquals("token-not-found-or-expired", result.reason());
    }

    @Test
    void expiredTokenFails() throws IOException {
        String token = pendingTokenWithSnapshot(new byte[] {1}, 10);
        clock.advance(11); // past the 10s TTL

        UnknownVisitorEnrollmentService.EnrollmentResult result = service.complete(token, "Someone");

        assertFalse(result.success());
        assertEquals("token-not-found-or-expired", result.reason());
    }

    @Test
    void faceClientEnrollmentFailureStillConsumesTheToken() throws IOException {
        String token = pendingTokenWithSnapshot(new byte[] {1, 2}, 300);
        faceClient.queueEnroll(FaceRecognitionClient.FaceEnrollResult.failure("blurry image"));

        UnknownVisitorEnrollmentService.EnrollmentResult result = service.complete(token, "Bob");

        assertFalse(result.success());
        assertEquals("blurry image", result.reason());
        assertTrue(pendingStore.get(token, clock.get()).isEmpty()); // consumed, not retryable
        assertTrue(peopleStore.all().isEmpty()); // no person created on failure
    }

    @Test
    void unreadableSnapshotFileFailsAndConsumesTheToken() {
        Path missing = tempDir.resolve("does-not-exist.jpg");
        String token = pendingStore.create("cam1", missing.toString(), clock.get(), 300).token();

        UnknownVisitorEnrollmentService.EnrollmentResult result = service.complete(token, "Bob");

        assertFalse(result.success());
        assertEquals("snapshot-unavailable", result.reason());
        assertTrue(pendingStore.get(token, clock.get()).isEmpty());
        assertTrue(faceClient.enrollCalls().isEmpty()); // never even reached the face client
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
