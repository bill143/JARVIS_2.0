package com.jarvis.app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Test-only fake for {@link FaceRecognitionClient} (no network, no CompreFace) — the hand-written
 * fake convention this app uses for functional-interface seams (see {@code APP_ARCHITECTURE.md}
 * §4.1/§4.6; compare the inline fakes for {@code OpenHumanTransport} and {@code LlmProvider}).
 *
 * <p>Tests queue up canned results (FIFO) for {@link #recognize} and {@link #enroll}; once a queue
 * is drained, calls fall back to a programmable default so a test can leave uninteresting calls
 * unprogrammed. Every call is recorded so a test can assert on what was sent.
 */
final class FakeFaceRecognitionClient implements FaceRecognitionClient {

    private final Deque<FaceMatchResult> queuedMatches = new ArrayDeque<>();
    private final Deque<FaceEnrollResult> queuedEnrolls = new ArrayDeque<>();
    private final List<byte[]> recognizeCalls = new ArrayList<>();
    private final List<String> enrollCalls = new ArrayList<>();

    private FaceMatchResult defaultMatch = FaceMatchResult.noMatch();
    private FaceEnrollResult defaultEnroll = FaceEnrollResult.failure("not programmed");

    /** Queues {@code result} to be returned by the next {@link #recognize} call. */
    FakeFaceRecognitionClient queueMatch(FaceMatchResult result) {
        queuedMatches.addLast(result);
        return this;
    }

    /** Queues {@code result} to be returned by the next {@link #enroll} call. */
    FakeFaceRecognitionClient queueEnroll(FaceEnrollResult result) {
        queuedEnrolls.addLast(result);
        return this;
    }

    /** Sets the result returned once the queued matches are exhausted (default: no-match). */
    FakeFaceRecognitionClient defaultMatch(FaceMatchResult result) {
        this.defaultMatch = result;
        return this;
    }

    /** Sets the result returned once the queued enrolls are exhausted (default: failure). */
    FakeFaceRecognitionClient defaultEnroll(FaceEnrollResult result) {
        this.defaultEnroll = result;
        return this;
    }

    /** The {@code imageBytes} passed to every {@link #recognize} call so far, in order. */
    List<byte[]> recognizeCalls() {
        return recognizeCalls;
    }

    /** The {@code personName} passed to every {@link #enroll} call so far, in order. */
    List<String> enrollCalls() {
        return enrollCalls;
    }

    @Override
    public FaceMatchResult recognize(byte[] imageBytes) {
        recognizeCalls.add(imageBytes);
        FaceMatchResult next = queuedMatches.pollFirst();
        return next != null ? next : defaultMatch;
    }

    @Override
    public FaceEnrollResult enroll(byte[] imageBytes, String personName) {
        enrollCalls.add(personName);
        FaceEnrollResult next = queuedEnrolls.pollFirst();
        return next != null ? next : defaultEnroll;
    }
}
