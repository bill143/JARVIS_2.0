package com.jarvis.agent.loop;

import com.jarvis.tools.ToolRegistry;
import com.jarvis.tools.ToolResult;
import java.util.Objects;

/**
 * Bounded iterate–decide–act–observe driver: the agent control loop pattern from
 * {@code isair/jarvis}, reduced to its mechanism. Each iteration asks the {@link AgentPolicy} for a
 * {@link Decision}; a {@code Respond} ends the run, an {@code Invoke} is dispatched through the
 * {@link ToolRegistry} and its observation appended to the context for the next iteration.
 *
 * <p>The loop is mechanism-only by design: no speech, intent classification, planning, or memory
 * recall here — those plug in through the policy (and later orchestration) seams. Tool failures do
 * not stop the loop; they are observations the policy sees on the next decision. The step budget
 * ({@code maxSteps}) is the only termination guarantee against a policy that never responds.
 */
public final class AgentLoop {

    private final AgentPolicy policy;
    private final ToolRegistry tools;
    private final int maxSteps;

    /**
     * @param policy decides the next action each iteration
     * @param tools registry used to execute {@code Invoke} decisions
     * @param maxSteps maximum tool executions per run; must be at least 1
     */
    public AgentLoop(AgentPolicy policy, ToolRegistry tools, int maxSteps) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.tools = Objects.requireNonNull(tools, "tools");
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be at least 1, got " + maxSteps);
        }
        this.maxSteps = maxSteps;
    }

    /** Runs the loop for {@code input} until the policy responds or the step budget is spent. */
    public AgentResult run(String input) {
        AgentContext context = AgentContext.initial(input);
        for (int step = 0; step < maxSteps; step++) {
            Decision decision = decide(context);
            if (decision instanceof Decision.Respond respond) {
                return AgentResult.responded(respond.message(), context.steps());
            }
            Decision.Invoke invoke = (Decision.Invoke) decision;
            ToolResult result = tools.execute(invoke.call());
            context = context.withStep(new AgentStep(invoke.call(), result));
        }
        // Budget spent; give the policy one last chance to answer, but allow no more tools.
        if (decide(context) instanceof Decision.Respond respond) {
            return AgentResult.responded(respond.message(), context.steps());
        }
        return AgentResult.maxStepsReached(context.steps());
    }

    private Decision decide(AgentContext context) {
        return Objects.requireNonNull(policy.decide(context), "policy returned null decision");
    }
}
