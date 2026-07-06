package com.jarvis.agent.loop;

import java.util.List;
import java.util.Objects;

/**
 * Final outcome of a loop run. Mirrors the {@code ToolResult} idiom: {@code response} is non-null
 * exactly when the agent actually responded — callers check {@link #stopReason()} first. Use the
 * {@link #responded(String, List)} and {@link #maxStepsReached(List)} factories.
 *
 * @param stopReason why the loop stopped
 * @param response the agent's reply; non-null exactly when {@code stopReason} is {@code RESPONDED}
 * @param steps immutable, ordered history of all steps taken during the run
 */
public record AgentResult(StopReason stopReason, String response, List<AgentStep> steps) {

    /** Why the loop stopped. */
    public enum StopReason {
        /** The policy produced a final response. */
        RESPONDED,
        /** The step budget ran out before the policy responded. */
        MAX_STEPS_REACHED
    }

    public AgentResult {
        Objects.requireNonNull(stopReason, "stopReason");
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (stopReason == StopReason.RESPONDED) {
            Objects.requireNonNull(response, "response must be non-null when RESPONDED");
        } else if (response != null) {
            throw new IllegalArgumentException("response must be null unless RESPONDED");
        }
    }

    /** Creates a result for a loop that finished with a response. */
    public static AgentResult responded(String response, List<AgentStep> steps) {
        return new AgentResult(StopReason.RESPONDED, response, steps);
    }

    /** Creates a result for a loop that exhausted its step budget. */
    public static AgentResult maxStepsReached(List<AgentStep> steps) {
        return new AgentResult(StopReason.MAX_STEPS_REACHED, null, steps);
    }
}
