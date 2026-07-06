package com.jarvis.planning;

import java.util.Objects;

/**
 * One ordered sub-task of a {@link Plan}.
 *
 * @param id identifier unique within the owning plan
 * @param description what this sub-task accomplishes
 * @param status current lifecycle state
 */
public record PlanStep(String id, String description, StepStatus status) {

    public PlanStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(status, "status");
    }

    /** Creates a step in the initial {@link StepStatus#PENDING} state. */
    public static PlanStep pending(String id, String description) {
        return new PlanStep(id, description, StepStatus.PENDING);
    }

    /** Returns a copy of this step with {@code status}; this step is unchanged. */
    public PlanStep withStatus(StepStatus status) {
        return new PlanStep(id, description, status);
    }
}
