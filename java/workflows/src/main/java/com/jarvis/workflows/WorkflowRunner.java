package com.jarvis.workflows;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Executes a workflow's steps in order through a pluggable {@link StepExecutor} (in production, the
 * agent). Hard-capped at {@link #MAX_STEPS} steps per run — a runaway budget guard — and stops at
 * the first failing step. Records each run to the {@link WorkflowRunStore} for the history view.
 */
public final class WorkflowRunner {

    /** Hard cap on steps executed in a single run. */
    public static final int MAX_STEPS = 20;

    /** Runs one step and returns its output, or throws to fail the step. */
    @FunctionalInterface
    public interface StepExecutor {
        String run(String step) throws Exception;
    }

    private final WorkflowRunStore runs;   // nullable

    public WorkflowRunner(WorkflowRunStore runs) {
        this.runs = runs;
    }

    public WorkflowRun run(Workflow workflow, StepExecutor executor, long nowMillis) {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(executor, "executor");
        List<StepResult> results = new ArrayList<>();
        boolean ok = true;
        int n = Math.min(workflow.steps().size(), MAX_STEPS);
        for (int i = 0; i < n; i++) {
            String step = workflow.steps().get(i);
            try {
                results.add(new StepResult(step, executor.run(step), true));
            } catch (Exception e) {
                results.add(new StepResult(step, "error: " + e.getMessage(), false));
                ok = false;
                break;   // stop the chain on failure
            }
        }
        WorkflowRun run = new WorkflowRun(
                "r-" + Long.toHexString(nowMillis) + "-" + workflow.id(), workflow.id(),
                workflow.name(), nowMillis, workflow.trigger(), results, ok);
        if (runs != null) {
            runs.append(run);
        }
        return run;
    }
}
