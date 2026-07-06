package com.jarvis.app;

import com.jarvis.agent.loop.AgentPolicy;
import com.jarvis.agent.loop.Decision;
import com.jarvis.agent.orchestration.Orchestrator;
import com.jarvis.agent.routing.PromptRouter;
import com.jarvis.api.DefaultJarvisApi;
import com.jarvis.api.JarvisApi;
import com.jarvis.integrations.llm.AnthropicPolicy;
import com.jarvis.memory.InMemoryStore;
import com.jarvis.planning.Plan;
import com.jarvis.planning.PlanStep;
import com.jarvis.planning.Planner;
import com.jarvis.tools.ToolRegistry;
import java.util.List;

/**
 * Composition root: assembles the platform into a usable {@link JarvisApi}.
 *
 * <p>With an Anthropic API key the fallback policy is Claude; without one the app still runs in an
 * offline echo mode so the pipeline can be exercised end-to-end before any key exists.
 */
final class AppWiring {

    static final String OFFLINE_HINT =
            "(offline mode - set the ANTHROPIC_API_KEY environment variable to enable AI) "
                    + "You said: ";

    private AppWiring() {
    }

    static JarvisApi buildApi(String apiKey, String model) {
        AgentPolicy policy = (apiKey == null || apiKey.isBlank())
                ? context -> new Decision.Respond(OFFLINE_HINT + context.input())
                : AnthropicPolicy.withApiKey(apiKey, model);
        PromptRouter<AgentPolicy> router = new PromptRouter<>(List.of());
        Planner planner = goal -> new Plan(goal, List.of(PlanStep.pending("goal", goal)));
        Orchestrator orchestrator = new Orchestrator(
                router, policy, new ToolRegistry(), planner, new InMemoryStore<>(), 4);
        return new DefaultJarvisApi(orchestrator);
    }

    static boolean isOnline(String apiKey) {
        return apiKey != null && !apiKey.isBlank();
    }
}
