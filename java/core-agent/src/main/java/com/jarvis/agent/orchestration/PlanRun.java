package com.jarvis.agent.orchestration;

import com.jarvis.agent.loop.AgentResult;
import com.jarvis.planning.Plan;
import java.util.List;
import java.util.Objects;

/**
 * Outcome of a plan-driven orchestration run: the plan with final step statuses, and the loop
 * result for each executed step in plan order.
 *
 * @param plan the plan after execution, every executed step in a terminal status
 * @param stepResults loop results for the executed steps, index-aligned with the plan's steps
 */
public record PlanRun(Plan plan, List<AgentResult> stepResults) {

    public PlanRun {
        Objects.requireNonNull(plan, "plan");
        stepResults = stepResults == null ? List.of() : List.copyOf(stepResults);
    }

    /** Returns whether every step completed successfully. */
    public boolean succeeded() {
        return plan.isComplete() && !plan.hasFailure();
    }
}
