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
import com.jarvis.integrations.mark.SystemControlPlugin;
import com.jarvis.integrations.mark.VisionTool;
import com.jarvis.memory.FileBackedStore;
import com.jarvis.memory.MemoryStore;
import com.jarvis.planning.Plan;
import com.jarvis.planning.PlanStep;
import com.jarvis.planning.Planner;
import com.jarvis.tools.ToolRegistry;
import java.nio.file.Path;
import java.util.List;

/**
 * Composition root: assembles the platform into a usable {@link JarvisApi}.
 *
 * <p>Memory is file-backed at {@code ~/.jarvis/memory.tsv} so reminders and session history
 * survive restarts. The Mark tool plugin and Windows system-control plugin are always installed;
 * screen vision is added only when an API key exists (it needs the vision API). Without a key the
 * app runs in offline echo mode.
 */
final class AppWiring {

    static final String OFFLINE_HINT =
            "(offline mode - set the ANTHROPIC_API_KEY environment variable to enable AI) "
                    + "You said: ";

    private AppWiring() {
    }

    /** Production wiring with durable memory in the user's home directory. */
    static JarvisApi buildApi(String apiKey, String model) {
        Path memoryFile = Path.of(System.getProperty("user.home"), ".jarvis", "memory.tsv");
        return buildApi(apiKey, model, new FileBackedStore(memoryFile));
    }

    /** Wiring with an injectable store (tests use an in-memory one). */
    static JarvisApi buildApi(String apiKey, String model, MemoryStore<String> memory) {
        ToolRegistry tools = new ToolRegistry();
        PluginManager plugins = new PluginManager(tools);
        plugins.install(new MarkToolsPlugin(memory));
        plugins.install(new SystemControlPlugin());

        AgentPolicy policy;
        if (isOnline(apiKey)) {
            tools.register(new VisionTool(AnthropicPolicy.anthropicTransport(apiKey), model));
            policy = AnthropicPolicy.withApiKey(apiKey, model, tools);
        } else {
            policy = context -> new Decision.Respond(OFFLINE_HINT + context.input());
        }
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
