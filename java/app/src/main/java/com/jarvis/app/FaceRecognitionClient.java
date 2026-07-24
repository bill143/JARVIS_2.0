package com.jarvis.app;

/**
 * Seam over a face-recognition provider (CompreFace in production). {@link #recognize} matches a
 * captured frame against enrolled subjects; {@link #enroll} teaches the provider a new subject
 * from a reference image. Consumers depend only on this interface, so the real HTTP adapter (a
 * later phase) and any test fake are interchangeable.
 *
 * <p>Both methods are synchronous/blocking; callers on a request thread are expected to run them
 * off the hot path where appropriate. Neither method throws for ordinary provider failures — those
 * are modeled as a result value ({@link FaceMatchResult.Status#ERROR} /
 * {@link FaceEnrollResult#failure}) so callers do not need a try/catch for the common
 * "provider unreachable" or "no face found" cases.
 */
interface FaceRecognitionClient {

    /** Attempts to match {@code imageBytes} against enrolled subjects. */
    FaceMatchResult recognize(byte[] imageBytes);

    /** Enrolls {@code imageBytes} as a new reference image for {@code personName}. */
    FaceEnrollResult enroll(byte[] imageBytes, String personName);

    /**
     * The outcome of a {@link #recognize} call.
     *
     * <ul>
     *   <li>{@link Status#MATCHED} — {@code subjectId} and {@code similarity} are populated.</li>
     *   <li>{@link Status#NO_MATCH} — the provider ran cleanly but found no confident match;
     *       {@code subjectId} is {@code null} and {@code similarity} is {@code 0.0}.</li>
     *   <li>{@link Status#ERROR} — the provider could not be reached or returned something
     *       unusable; {@code message} carries a human-readable reason.</li>
     * </ul>
     */
    record FaceMatchResult(Status status, String subjectId, double similarity, String message) {

        enum Status { MATCHED, NO_MATCH, ERROR }

        static FaceMatchResult matched(String subjectId, double similarity) {
            return new FaceMatchResult(Status.MATCHED, subjectId, similarity, null);
        }

        static FaceMatchResult noMatch() {
            return new FaceMatchResult(Status.NO_MATCH, null, 0.0, null);
        }

        static FaceMatchResult error(String message) {
            return new FaceMatchResult(Status.ERROR, null, 0.0, message);
        }
    }

    /**
     * The outcome of an {@link #enroll} call: either {@code success} with the provider-assigned
     * {@code subjectId}, or a failure with a {@code reason} and no subject id.
     */
    record FaceEnrollResult(boolean success, String subjectId, String reason) {

        static FaceEnrollResult success(String subjectId) {
            return new FaceEnrollResult(true, subjectId, null);
        }

        static FaceEnrollResult failure(String reason) {
            return new FaceEnrollResult(false, null, reason);
        }
    }
}
