package com.jarvis.api;

import com.jarvis.agent.loop.AgentResult;
import com.jarvis.agent.orchestration.Orchestrator;
import com.jarvis.agent.orchestration.PlanRun;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Default {@link JarvisApi} backed by the {@link Orchestrator}: a thin mapping layer between the
 * public request/response types and the platform's internal results, with no logic of its own.
 */
public final class DefaultJarvisApi implements JarvisApi {

    private final Orchestrator orchestrator;

    public DefaultJarvisApi(Orchestrator orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        AgentResult result = orchestrator.handle(request.sessionId(), request.prompt());
        return new ChatResponse(
                request.sessionId(),
                result.stopReason() == AgentResult.StopReason.RESPONDED,
                result.response(),
                result.steps().size());
    }

    @Override
    public PlanResponse plan(PlanRequest request) {
        Objects.requireNonNull(request, "request");
        PlanRun run = orchestrator.handlePlan(request.sessionId(), request.goal());
        List<PlanResponse.StepOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < run.stepResults().size(); i++) {
            AgentResult result = run.stepResults().get(i);
            outcomes.add(new PlanResponse.StepOutcome(
                    run.plan().steps().get(i).description(),
                    result.stopReason() == AgentResult.StopReason.RESPONDED,
                    result.response()));
        }
        return new PlanResponse(request.sessionId(), run.succeeded(), outcomes);
    }
}
