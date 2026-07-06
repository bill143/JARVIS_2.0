package com.jarvis.api;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of a plan-driven run.
 *
 * @param sessionId the conversation the run belongs to
 * @param succeeded whether every step completed successfully
 * @param stepOutcomes one entry per executed plan step, in plan order
 */
public record PlanResponse(String sessionId, boolean succeeded, List<StepOutcome> stepOutcomes) {

    /**
     * Outcome of one plan step.
     *
     * @param description the sub-task that was executed
     * @param completed whether the step's agent run produced a response
     * @param response the step's reply; non-null exactly when {@code completed}
     */
    public record StepOutcome(String description, boolean completed, String response) {
        public StepOutcome {
            Objects.requireNonNull(description, "description");
            if (completed) {
                Objects.requireNonNull(response, "response must be non-null when completed");
            } else if (response != null) {
                throw new IllegalArgumentException("response must be null when not completed");
            }
        }
    }

    public PlanResponse {
        Objects.requireNonNull(sessionId, "sessionId");
        stepOutcomes = stepOutcomes == null ? List.of() : List.copyOf(stepOutcomes);
    }
}
