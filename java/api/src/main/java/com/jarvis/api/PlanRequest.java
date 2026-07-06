package com.jarvis.api;

import java.util.Objects;

/**
 * A goal submitted for plan-driven execution.
 *
 * @param sessionId conversation the run belongs to; also the memory scope step exchanges are
 *     recorded under
 * @param goal what to accomplish
 */
public record PlanRequest(String sessionId, String goal) {

    public PlanRequest {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(goal, "goal");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
    }
}
