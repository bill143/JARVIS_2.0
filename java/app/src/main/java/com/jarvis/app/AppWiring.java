package com.jarvis.app;

import com.jarvis.agent.loop.AgentPolicy;
import com.jarvis.agent.loop.Decision;
import com.jarvis.agent.orchestration.Orchestrator;
import com.jarvis.agent.routing.PromptRouter;
import com.jarvis.api.DefaultJarvisApi;
import com.jarvis.api.JarvisApi;
import com.jarvis.integrations.PluginManager;
import com.jarvis.integrations.llm.AnthropicPolicy;
import com.jarvis.integrations.mark.MarkToolsPlugin;
import com.jarvis.memory.InMemoryStore;
import com.jarvis.memory.MemoryStore;
import com.jarvis.planning.Plan;
import com.jarvis.planning.PlanStep;
import com.jarvis.planning.Planner;
import com.jarvis.tools.ToolRegistry;
import java.util.List;

/**
 * Composition root: assembles the platform into a usable {@link JarvisApi}.
 *
 * <p>The Mark-inspired tool plugin is always installed. With an Anthropic API key the fallback
 * policy is a tool-aware Claude; without one the app still runs in an offline echo mode so the
 * pipeline can be exercised end-to-end before any key exists.
 */
final class AppWiring {

    static final String OFFLINE_HINT =
            "(offline mode - set the ANTHROPIC_API_KEY environment variable to enable AI) "
                    + "You said: ";

    private AppWiring() {
    }

    static JarvisApi buildApi(String apiKey, String model) {
        MemoryStore<String> memory = new InMemoryStore<>();
        ToolRegistry tools = new ToolRegistry();
        new PluginManager(tools).install(new MarkToolsPlugin(memory));

        AgentPolicy policy = isOnline(apiKey)
                ? AnthropicPolicy.withApiKey(apiKey, model, tools)
                : context -> new Decision.Respond(OFFLINE_HINT + context.input());
        PromptRouter<AgentPolicy> router = new PromptRouter<>(List.of());
        Planner planner = goal -> new Plan(goal, List.of(PlanStep.pending("goal", goal)));
        // Budget 6: a briefing legitimately chains clock + reminders + news before answering.
        Orchestrator orchestrator = new Orchestrator(router, policy, tools, planner, memory, 6);
        return new DefaultJarvisApi(orchestrator);
    }

    static boolean isOnline(String apiKey) {
        return apiKey != null && !apiKey.isBlank();
    }
}
