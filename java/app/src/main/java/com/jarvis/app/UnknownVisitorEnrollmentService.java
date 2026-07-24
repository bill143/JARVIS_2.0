package com.jarvis.app;

import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.tools.RiskTier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Completes the enrollment of an unknown visitor once JARVIS has asked their name: reads the
 * pending snapshot back from disk, enrolls it with the face-recognition provider, then attaches
 * the resulting subject id to either an existing {@link PeopleStore.Person} (matched by name) or a
 * brand-new one.
 */
final class UnknownVisitorEnrollmentService {

    /** The outcome of completing an enrollment. */
    record EnrollmentResult(boolean success, String reason, String personId, String personName) {

        static EnrollmentResult failure(String reason) {
            return new EnrollmentResult(false, reason, null, null);
        }
    }

    private final FaceRecognitionClient faceClient;
    private final PeopleStore peopleStore;
    private final PendingVisitorStore pendingStore;
    private final Supplier<Instant> clock;
    private final AuditLog audit; // nullable -> no audit trail

    UnknownVisitorEnrollmentService(FaceRecognitionClient faceClient, PeopleStore peopleStore,
            PendingVisitorStore pendingStore, Supplier<Instant> clock, AuditLog audit) {
        this.faceClient = Objects.requireNonNull(faceClient, "faceClient");
        this.peopleStore = Objects.requireNonNull(peopleStore, "peopleStore");
        this.pendingStore = Objects.requireNonNull(pendingStore, "pendingStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.audit = audit;
    }

    EnrollmentResult complete(String pendingToken, String name) {
        if (name == null || name.isBlank()) {
            recordAudit(AuditOutcome.FAILURE, "reason=name-required pendingToken=" + pendingToken);
            return EnrollmentResult.failure("name-required");
        }

        Instant now = clock.get();
        Optional<PendingVisitorStore.PendingVisitor> pending = pendingStore.get(pendingToken, now);
        if (pending.isEmpty()) {
            recordAudit(AuditOutcome.FAILURE,
                    "reason=token-not-found-or-expired pendingToken=" + pendingToken + " name=" + name);
            return EnrollmentResult.failure("token-not-found-or-expired");
        }
        PendingVisitorStore.PendingVisitor visitor = pending.get();

        byte[] imageBytes;
        try {
            imageBytes = Files.readAllBytes(Path.of(visitor.snapshotPath()));
        } catch (IOException e) {
            pendingStore.consume(pendingToken);
            recordAudit(AuditOutcome.FAILURE,
                    "reason=snapshot-unavailable pendingToken=" + pendingToken + " name=" + name);
            return EnrollmentResult.failure("snapshot-unavailable");
        }

        FaceRecognitionClient.FaceEnrollResult enrollResult = faceClient.enroll(imageBytes, name);
        if (!enrollResult.success()) {
            pendingStore.consume(pendingToken);
            recordAudit(AuditOutcome.FAILURE,
                    "reason=" + enrollResult.reason() + " pendingToken=" + pendingToken + " name=" + name);
            return EnrollmentResult.failure(enrollResult.reason());
        }

        String subjectId = enrollResult.subjectId();
        String personId;
        Optional<PeopleStore.Person> existing = peopleStore.findByNameIgnoreCase(name);
        if (existing.isPresent()) {
            personId = existing.get().id();
            peopleStore.enrollFaceSubject(personId, subjectId, name);
        } else {
            personId = peopleStore.addWithFaceSubject(name, subjectId, name);
        }
        peopleStore.recordSighting(personId, now.toString());
        pendingStore.consume(pendingToken);
        if (audit != null) {
            audit.record(new AuditEvent(AuditCategory.EXTERNAL_API, "VISION_ENROLLMENT_COMPLETED",
                    AuditTrigger.USER, RiskTier.MUTATING, AuditOutcome.SUCCESS,
                    "personId=" + personId + " personName=" + name));
        }
        return new EnrollmentResult(true, null, personId, name);
    }

    private void recordAudit(AuditOutcome outcome, String detail) {
        if (audit != null) {
            audit.record(new AuditEvent(AuditCategory.EXTERNAL_API, "VISION_ENROLLMENT_FAILED",
                    AuditTrigger.USER, RiskTier.MUTATING, outcome, detail));
        }
    }
}
