package com.jarvis.agent.orchestration;

import com.jarvis.agent.loop.AgentLoop;
import com.jarvis.agent.loop.AgentPolicy;
import com.jarvis.agent.loop.AgentResult;
import com.jarvis.agent.routing.PromptRouter;
import com.jarvis.memory.MemoryStore;
import com.jarvis.planning.Plan;
import com.jarvis.planning.PlanStep;
import com.jarvis.planning.Planner;
import com.jarvis.planning.StepStatus;
import com.jarvis.tools.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * End-to-end request coordinator: the orchestration pattern from {@code open-jarvis/OpenJarvis}
 * (input → agent selection → skill invocation → response), wired from this project's seams.
 *
 * <p>For each request the orchestrator selects a policy via the {@link PromptRouter} (falling back
 * to a default), drives an {@link AgentLoop} against the {@link ToolRegistry}, records the exchange
 * in the {@link MemoryStore} under the session's scope, and returns the loop result. Plan-driven
 * runs decompose a goal with the {@link Planner} and march each sub-task through the same path,
 * marking steps {@code COMPLETED} or {@code FAILED} from their loop outcomes.
 *
 * <p>Mechanism-only: no LLM, no speech, no scheduling — those arrive behind the existing seams.
 */
public final class Orchestrator {

    private final PromptRouter<AgentPolicy> router;
    private final AgentPolicy fallbackPolicy;
    private final ToolRegistry tools;
    private final Planner planner;
    private final MemoryStore<String> memory;
    private final int maxStepsPerRun;

    /**
     * @param router selects the policy for a prompt; first match wins
     * @param fallbackPolicy used when no route matches
     * @param tools registry the loop dispatches tool calls through
     * @param planner decomposes goals for {@link #handlePlan(String, String)}
     * @param memory session history store; exchanges are recorded under the session id's scope
     * @param maxStepsPerRun step budget for each loop run; must be at least 1
     */
    public Orchestrator(
            PromptRouter<AgentPolicy> router,
            AgentPolicy fallbackPolicy,
            ToolRegistry tools,
            Planner planner,
            MemoryStore<String> memory,
            int maxStepsPerRun) {
        this.router = Objects.requireNonNull(router, "router");
        this.fallbackPolicy = Objects.requireNonNull(fallbackPolicy, "fallbackPolicy");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.memory = Objects.requireNonNull(memory, "memory");
        if (maxStepsPerRun < 1) {
            throw new IllegalArgumentException(
                    "maxStepsPerRun must be at least 1, got " + maxStepsPerRun);
        }
        this.maxStepsPerRun = maxStepsPerRun;
    }

    /**
     * Handles one prompt for {@code sessionId}: route to a policy, run the loop, record the
     * exchange in session memory, return the result.
     */
    public AgentResult handle(String sessionId, String prompt) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(prompt, "prompt");
        AgentPolicy policy = router.routeOrDefault(prompt, fallbackPolicy);
        AgentResult result = new AgentLoop(policy, tools, maxStepsPerRun).run(prompt);
        record(sessionId, prompt, result);
        return result;
    }

    /**
     * Decomposes {@code goal} with the planner and handles each sub-task in plan order through the
     * same route-loop-record path. A step is {@code COMPLETED} when its loop responded and
     * {@code FAILED} when the step budget ran out; execution continues past failures so the
     * returned plan reflects every step's outcome.
     */
    public PlanRun handlePlan(String sessionId, String goal) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(goal, "goal");
        Plan plan = Objects.requireNonNull(planner.plan(goal), "planner returned null plan");
        List<AgentResult> results = new ArrayList<>();
        while (plan.nextPending().isPresent()) {
            PlanStep step = plan.nextPending().orElseThrow();
            plan = plan.withStepStatus(step.id(), StepStatus.IN_PROGRESS);
            AgentResult result = handle(sessionId, step.description());
            results.add(result);
            StepStatus outcome = result.stopReason() == AgentResult.StopReason.RESPONDED
                    ? StepStatus.COMPLETED
                    : StepStatus.FAILED;
            plan = plan.withStepStatus(step.id(), outcome);
        }
        return new PlanRun(plan, results);
    }

    private void record(String sessionId, String prompt, AgentResult result) {
        String key = "turn-" + memory.query(sessionId).size();
        String value = result.stopReason() == AgentResult.StopReason.RESPONDED
                ? prompt + " -> " + result.response()
                : prompt + " -> <no response>";
        memory.put(sessionId, key, value, Map.of(
                "stopReason", result.stopReason().name(),
                "toolSteps", String.valueOf(result.steps().size())));
    }
}
