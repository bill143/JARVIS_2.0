package com.jarvis.autonomous;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The AutoGPT-style autonomous loop: given a goal, it repeatedly asks the agent for the next step,
 * feeding back the progress so far, until the agent signals completion ({@link #DONE_MARKER}) or the
 * hard step budget is spent.
 *
 * <p>Safety by construction: the loop can never run more than {@link #MAX_STEPS} iterations — a hard
 * budget guard — and it does no I/O itself; every action goes through the injected {@link AgentStep},
 * which in production is the governed agent (permission + audit gated).
 */
public final class AutonomousRunner {

    /** Absolute ceiling on iterations, regardless of configuration. */
    public static final int MAX_STEPS = 10;

    /** The token the agent emits when the goal is achieved. */
    public static final String DONE_MARKER = "GOAL_DONE";

    /** One iteration: given the goal and the progress so far, produce the next action's result. */
    @FunctionalInterface
    public interface AgentStep {
        String step(String goal, String progressSoFar) throws Exception;
    }

    /** The result of an autonomous run. */
    public record AutonomousRun(String goal, List<String> steps, boolean completed) {
    }

    private final int maxSteps;

    public AutonomousRunner() {
        this(MAX_STEPS);
    }

    public AutonomousRunner(int maxSteps) {
        this.maxSteps = Math.min(Math.max(1, maxSteps), MAX_STEPS);
    }

    public AutonomousRun run(String goal, AgentStep agent) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(agent, "agent");
        List<String> steps = new ArrayList<>();
        boolean completed = false;
        for (int i = 0; i < maxSteps; i++) {
            String progress = steps.isEmpty() ? "(nothing done yet)" : String.join("\n", steps);
            String out;
            try {
                out = agent.step(goal, progress);
            } catch (Exception e) {
                out = "(error: " + e.getMessage() + ")";
            }
            if (out == null) {
                out = "";
            }
            steps.add(out);
            if (out.contains(DONE_MARKER)) {
                completed = true;
                break;
            }
        }
        return new AutonomousRun(goal, steps, completed);
    }
}
