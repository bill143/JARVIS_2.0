package com.jarvis.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.memory.RecordStore;
import com.jarvis.tools.RiskTier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Handles a motion webhook payload end-to-end: cooldown, image extraction, face recognition,
 * pending-visitor creation, greeting, and visit-log append.
 *
 * <p><b>Inert Default Behavior.</b> When {@code visionSettings.snapshot().motion().enabled()} is
 * {@code false}, {@link #handle} does absolutely nothing beyond returning a rejected result — no
 * cooldown bookkeeping, no image extraction, no face-recognition call, no pending-visitor entry, no
 * visit-log append. This is the single most important invariant of the whole vision feature: it
 * must be fully off by default and stay fully off until explicitly enabled.
 */
final class MotionEventService {

    /** Seam for fetching a snapshot image from a camera-provided URL. */
    @FunctionalInterface
    interface SnapshotFetcher {
        byte[] fetch(String url) throws IOException;
    }

    /** One inbound motion webhook payload. Exactly one of {@code imageBase64}/{@code snapshotUrl}
     * is expected to be populated. */
    record MotionEventRequest(String cameraId, String timestamp, String imageBase64, String snapshotUrl) {
    }

    /** The outcome of handling one motion event. */
    record MotionEventResult(boolean accepted, String reason, String greeting, boolean recognized,
            String personId, String personName, String pendingToken) {

        static MotionEventResult rejected(String reason) {
            return new MotionEventResult(false, reason, null, false, null, null, null);
        }
    }

    private static final String VISIT_LOG_COLLECTION = "vision-visits";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FaceRecognitionClient faceClient;
    private final VisionSettings visionSettings;
    private final PeopleStore peopleStore;
    private final PendingVisitorStore pendingStore;
    private final PresenceGreetingService greetingService;
    private final RecordStore visitLog; // nullable -> no logging
    private final SnapshotFetcher snapshotFetcher;
    private final Supplier<Instant> clock;
    private final AuditLog audit; // nullable -> no audit trail

    private final ConcurrentHashMap<String, Instant> lastEventByCamera = new ConcurrentHashMap<>();

    MotionEventService(FaceRecognitionClient faceClient, VisionSettings visionSettings,
            PeopleStore peopleStore, PendingVisitorStore pendingStore,
            PresenceGreetingService greetingService, RecordStore visitLog,
            SnapshotFetcher snapshotFetcher, Supplier<Instant> clock, AuditLog audit) {
        this.faceClient = Objects.requireNonNull(faceClient, "faceClient");
        this.visionSettings = Objects.requireNonNull(visionSettings, "visionSettings");
        this.peopleStore = Objects.requireNonNull(peopleStore, "peopleStore");
        this.pendingStore = Objects.requireNonNull(pendingStore, "pendingStore");
        this.greetingService = Objects.requireNonNull(greetingService, "greetingService");
        this.visitLog = visitLog;
        this.snapshotFetcher = Objects.requireNonNull(snapshotFetcher, "snapshotFetcher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.audit = audit;
    }

    MotionEventResult handle(MotionEventRequest request) {
        VisionSettings.Snapshot settings = visionSettings.snapshot();
        if (!settings.motion().enabled()) {
            // Fully inert: no cooldown bookkeeping, no image extraction, nothing persisted.
            return MotionEventResult.rejected("motion-disabled");
        }

        String cameraId = request.cameraId() == null ? "" : request.cameraId();
        Instant now = clock.get();
        // Fuller retention sweep (Phase 5): cheap housekeeping, run on every processed motion event
        // regardless of cooldown so stale pending-visitor snapshots don't pile up.
        pendingStore.pruneExpired(now);

        Instant last = lastEventByCamera.get(cameraId);
        if (last != null && now.isBefore(last.plusSeconds(settings.motion().cooldownSec()))) {
            return MotionEventResult.rejected("cooldown");
        }
        lastEventByCamera.put(cameraId, now);
        recordAudit(new AuditEvent(AuditCategory.SYSTEM, "VISION_MOTION_RECEIVED", AuditTrigger.SYSTEM,
                RiskTier.READ_ONLY, AuditOutcome.SUCCESS,
                "camera=" + cameraId + " timestamp=" + request.timestamp()));

        byte[] imageBytes;
        try {
            imageBytes = extractImage(request);
        } catch (ImageExtractionFailed e) {
            return MotionEventResult.rejected("image-extract-failed");
        }
        if (imageBytes == null) {
            return MotionEventResult.rejected("no-image");
        }

        if (!settings.face().enabled()) {
            appendVisit(cameraId, request.timestamp(), false, null, null, "face-disabled", null);
            return new MotionEventResult(true, "motion-only", null, false, null, null, null);
        }

        FaceRecognitionClient.FaceMatchResult match = faceClient.recognize(imageBytes);
        if (match.status() == FaceRecognitionClient.FaceMatchResult.Status.MATCHED) {
            Optional<PeopleStore.Person> person = peopleStore.findByFaceSubject(match.subjectId());
            if (person.isPresent()) {
                PeopleStore.Person p = person.get();
                peopleStore.recordSighting(p.id(), now.toString());
                String greeting = greetingService.knownGreeting(p);
                recordAudit(new AuditEvent(AuditCategory.EXTERNAL_API, "VISION_FACE_RECOGNIZED",
                        AuditTrigger.SYSTEM, RiskTier.READ_ONLY, AuditOutcome.SUCCESS,
                        "camera=" + cameraId + " personId=" + p.id() + " similarity=" + match.similarity()));
                appendVisit(cameraId, request.timestamp(), true, p.id(), p.name(), null, null);
                return new MotionEventResult(true, null, greeting, true, p.id(), p.name(), null);
            }
            // Data drift: a matched subject id with no local owner is treated like NO_MATCH below.
        }

        String reason = match.status() == FaceRecognitionClient.FaceMatchResult.Status.ERROR
                ? "face-recognition-error"
                : null;
        String errorMessage = match.status() == FaceRecognitionClient.FaceMatchResult.Status.ERROR
                ? match.message()
                : null;
        return handleUnknownVisitor(settings, cameraId, request.timestamp(), imageBytes, now, reason,
                errorMessage);
    }

    private MotionEventResult handleUnknownVisitor(VisionSettings.Snapshot settings, String cameraId,
            String timestamp, byte[] imageBytes, Instant now, String reason, String errorMessage) {
        String snapshotPath;
        try {
            snapshotPath = persistSnapshot(settings.storageRoot(), imageBytes);
        } catch (IOException e) {
            return MotionEventResult.rejected("snapshot-write-failed");
        }
        PendingVisitorStore.PendingVisitor pending = pendingStore.create(cameraId, snapshotPath, now,
                settings.face().pendingTtlSec());
        String greeting = greetingService.unknownGreeting();
        boolean isError = "face-recognition-error".equals(reason);
        String detail = isError
                ? "camera=" + cameraId + " pendingToken=" + pending.token() + " providerError=" + errorMessage
                : "camera=" + cameraId + " pendingToken=" + pending.token();
        recordAudit(new AuditEvent(AuditCategory.EXTERNAL_API, "VISION_FACE_UNKNOWN", AuditTrigger.SYSTEM,
                RiskTier.READ_ONLY, isError ? AuditOutcome.FAILURE : AuditOutcome.SUCCESS, detail));
        appendVisit(cameraId, timestamp, false, null, null, reason, pending.token());
        return new MotionEventResult(true, reason, greeting, false, null, null, pending.token());
    }

    private void recordAudit(AuditEvent event) {
        if (audit != null) {
            audit.record(event);
        }
    }

    private String persistSnapshot(String storageRoot, byte[] imageBytes) throws IOException {
        Path dir = Path.of(storageRoot);
        Files.createDirectories(dir);
        Path file = dir.resolve(UUID.randomUUID() + ".jpg");
        Files.write(file, imageBytes);
        return file.toString();
    }

    private byte[] extractImage(MotionEventRequest request) throws ImageExtractionFailed {
        String b64 = request.imageBase64();
        if (b64 != null && !b64.isBlank()) {
            try {
                return Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException e) {
                throw new ImageExtractionFailed(e);
            }
        }
        String url = request.snapshotUrl();
        if (url != null && !url.isBlank()) {
            try {
                return snapshotFetcher.fetch(url);
            } catch (IOException e) {
                throw new ImageExtractionFailed(e);
            }
        }
        return null; // caller reports "no-image"
    }

    private void appendVisit(String cameraId, String timestamp, boolean recognized, String personId,
            String personName, String note, String pendingToken) {
        if (visitLog == null) {
            return;
        }
        ObjectNode o = MAPPER.createObjectNode();
        o.put("cameraId", cameraId);
        o.put("timestamp", timestamp == null ? "" : timestamp);
        o.put("recognized", recognized);
        if (personId != null) {
            o.put("personId", personId);
        }
        if (personName != null) {
            o.put("personName", personName);
        }
        if (note != null) {
            o.put("note", note);
        }
        if (pendingToken != null) {
            o.put("pendingToken", pendingToken);
        }
        visitLog.append(VISIT_LOG_COLLECTION, o.toString());
    }

    /** Internal marker: an image could not be decoded/fetched. Never escapes {@link #handle}. */
    private static final class ImageExtractionFailed extends Exception {
        ImageExtractionFailed(Throwable cause) {
            super(cause);
        }
    }
}
