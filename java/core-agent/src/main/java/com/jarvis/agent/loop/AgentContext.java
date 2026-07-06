package com.jarvis.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable state the policy decides against: the original input plus every step taken so far, in
 * order. The loop appends observations by deriving a new context via {@link #withStep(AgentStep)}.
 *
 * @param input the original request the agent is working on
 * @param steps immutable, ordered history of completed steps
 */
public record AgentContext(String input, List<AgentStep> steps) {

    public AgentContext {
        Objects.requireNonNull(input, "input");
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /** Creates the initial context for {@code input} with no steps taken. */
    public static AgentContext initial(String input) {
        return new AgentContext(input, List.of());
    }

    /** Returns a new context with {@code step} appended; this context is unchanged. */
    public AgentContext withStep(AgentStep step) {
        Objects.requireNonNull(step, "step");
        List<AgentStep> extended = new ArrayList<>(steps);
        extended.add(step);
        return new AgentContext(input, extended);
    }
}
