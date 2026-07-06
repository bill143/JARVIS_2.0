package com.jarvis.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlanTest {

    private static Plan threeStepPlan() {
        return new Plan("make tea", List.of(
                PlanStep.pending("boil", "boil water"),
                PlanStep.pending("steep", "steep the tea"),
                PlanStep.pending("pour", "pour into cup")));
    }

    @Test
    void plannerSeamProducesOrderedPlan() {
        Planner planner = goal -> new Plan(goal, List.of(
                PlanStep.pending("s1", "first"),
                PlanStep.pending("s2", "second")));

        Plan plan = planner.plan("do the thing");
        assertEquals("do the thing", plan.goal());
        assertEquals(List.of("s1", "s2"), plan.steps().stream().map(PlanStep::id).toList());
    }

    @Test
    void nextPendingFollowsPlanOrder() {
        Plan plan = threeStepPlan();
        assertEquals("boil", plan.nextPending().orElseThrow().id());

        plan = plan.withStepStatus("boil", StepStatus.COMPLETED);
        assertEquals("steep", plan.nextPending().orElseThrow().id());
    }

    @Test
    void statusUpdateIsImmutableDerivation() {
        Plan original = threeStepPlan();
        Plan updated = original.withStepStatus("boil", StepStatus.IN_PROGRESS);

        assertEquals(StepStatus.PENDING,
                original.steps().getFirst().status());
        assertEquals(StepStatus.IN_PROGRESS,
                updated.steps().getFirst().status());
        // Order and other steps are untouched.
        assertEquals(List.of("boil", "steep", "pour"),
                updated.steps().stream().map(PlanStep::id).toList());
    }

    @Test
    void completionRequiresEveryStepTerminal() {
        Plan plan = threeStepPlan();
        assertFalse(plan.isComplete());

        plan = plan.withStepStatus("boil", StepStatus.COMPLETED)
                .withStepStatus("steep", StepStatus.COMPLETED);
        assertFalse(plan.isComplete());

        plan = plan.withStepStatus("pour", StepStatus.FAILED);
        assertTrue(plan.isComplete());
        assertTrue(plan.hasFailure());
        assertTrue(plan.nextPending().isEmpty());
    }

    @Test
    void inProgressIsNotTerminalAndNotPending() {
        Plan plan = threeStepPlan().withStepStatus("boil", StepStatus.IN_PROGRESS);
        assertEquals("steep", plan.nextPending().orElseThrow().id());
        assertFalse(plan.isComplete());
        assertFalse(StepStatus.IN_PROGRESS.isTerminal());
        assertTrue(StepStatus.COMPLETED.isTerminal());
        assertTrue(StepStatus.FAILED.isTerminal());
    }

    @Test
    void unknownStepIdFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> threeStepPlan().withStepStatus("absent", StepStatus.COMPLETED));
    }

    @Test
    void duplicateStepIdsAreRejected() {
        List<PlanStep> duplicated = List.of(
                PlanStep.pending("same", "one"),
                PlanStep.pending("same", "two"));
        assertThrows(IllegalArgumentException.class, () -> new Plan("goal", duplicated));
    }

    @Test
    void emptyPlanIsTriviallyComplete() {
        Plan plan = new Plan("nothing to do", List.of());
        assertTrue(plan.isComplete());
        assertFalse(plan.hasFailure());
        assertTrue(plan.nextPending().isEmpty());
    }

    @Test
    void stepListIsImmutable() {
        Plan plan = threeStepPlan();
        assertThrows(UnsupportedOperationException.class,
                () -> plan.steps().add(PlanStep.pending("x", "extra")));
    }
}
