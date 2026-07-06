package com.jarvis.planning;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable decomposition of a goal into an ordered list of sub-tasks. Progress is tracked by
 * deriving a new plan via {@link #withStepStatus(String, StepStatus)} — mirroring the
 * context-evolution idiom of the agent loop.
 *
 * @param goal the goal this plan accomplishes
 * @param steps ordered sub-tasks; step ids must be unique within the plan
 */
public record Plan(String goal, List<PlanStep> steps) {

    public Plan {
        Objects.requireNonNull(goal, "goal");
        steps = steps == null ? List.of() : List.copyOf(steps);
        long distinctIds = steps.stream().map(PlanStep::id).distinct().count();
        if (distinctIds != steps.size()) {
            throw new IllegalArgumentException("plan step ids must be unique");
        }
    }

    /** Returns the first step still in {@link StepStatus#PENDING}, in plan order. */
    public Optional<PlanStep> nextPending() {
        return steps.stream().filter(s -> s.status() == StepStatus.PENDING).findFirst();
    }

    /** Returns whether every step has reached a terminal status. */
    public boolean isComplete() {
        return steps.stream().allMatch(s -> s.status().isTerminal());
    }

    /** Returns whether any step has {@link StepStatus#FAILED}. */
    public boolean hasFailure() {
        return steps.stream().anyMatch(s -> s.status() == StepStatus.FAILED);
    }

    /**
     * Returns a new plan with the status of step {@code stepId} replaced; this plan is unchanged.
     *
     * @throws IllegalArgumentException if no step has {@code stepId}
     */
    public Plan withStepStatus(String stepId, StepStatus status) {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(status, "status");
        if (steps.stream().noneMatch(s -> s.id().equals(stepId))) {
            throw new IllegalArgumentException("no step with id: " + stepId);
        }
        List<PlanStep> updated = steps.stream()
                .map(s -> s.id().equals(stepId) ? s.withStatus(status) : s)
                .toList();
        return new Plan(goal, updated);
    }
}
