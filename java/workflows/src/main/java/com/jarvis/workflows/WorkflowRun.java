package com.jarvis.workflows;

import java.util.List;
import java.util.Objects;

/**
 * One execution of a workflow: its step results and whether it finished cleanly.
 *
 * @param id run id
 * @param workflowId which workflow ran
 * @param workflowName its name at run time
 * @param startedAtMillis when it started
 * @param trigger what triggered it
 * @param steps per-step results
 * @param ok whether every step succeeded
 */
public record WorkflowRun(String id, String workflowId, String workflowName, long startedAtMillis,
        TriggerType trigger, List<StepResult> steps, boolean ok) {

    public WorkflowRun {
        Objects.requireNonNull(id, "id");
        steps = steps == null ? List.of() : List.copyOf(steps);
        trigger = trigger == null ? TriggerType.MANUAL : trigger;
    }
}
