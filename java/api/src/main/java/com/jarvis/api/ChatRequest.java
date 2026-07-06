package com.jarvis.api;

import java.util.Objects;

/**
 * One chat turn submitted to the platform.
 *
 * @param sessionId conversation the turn belongs to; also the memory scope exchanges are recorded
 *     under
 * @param prompt what the user asked
 */
public record ChatRequest(String sessionId, String prompt) {

    public ChatRequest {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(prompt, "prompt");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
    }
}
