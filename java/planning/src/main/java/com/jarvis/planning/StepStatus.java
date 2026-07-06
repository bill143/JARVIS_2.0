package com.jarvis.planning;

/** Lifecycle of a single {@link PlanStep}. */
public enum StepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED;

    /** Returns whether this status is terminal (the step will not be worked on further). */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
