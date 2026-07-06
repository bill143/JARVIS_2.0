package com.jarvis.api;

import java.util.Objects;

/**
 * Outcome of one chat turn. Follows the platform's result idiom: {@code response} is non-null
 * exactly when {@code completed} is {@code true} — an incomplete turn means the agent's step
 * budget ran out before it could answer.
 *
 * @param sessionId the conversation the turn belongs to
 * @param completed whether the agent produced a response
 * @param response the agent's reply; non-null exactly when {@code completed}
 * @param toolSteps how many tool executions the turn used
 */
public record ChatResponse(String sessionId, boolean completed, String response, int toolSteps) {

    public ChatResponse {
        Objects.requireNonNull(sessionId, "sessionId");
        if (completed) {
            Objects.requireNonNull(response, "response must be non-null when completed");
        } else if (response != null) {
            throw new IllegalArgumentException("response must be null when not completed");
        }
        if (toolSteps < 0) {
            throw new IllegalArgumentException("toolSteps must not be negative, got " + toolSteps);
        }
    }
}
