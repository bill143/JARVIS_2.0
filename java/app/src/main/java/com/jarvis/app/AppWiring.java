package com.jarvis.app;

import com.jarvis.agent.loop.AgentPolicy;
import com.jarvis.agent.loop.Decision;
import com.jarvis.agent.orchestration.Orchestrator;
import com.jarvis.agent.routing.PromptRouter;
import com.jarvis.api.DefaultJarvisApi;
import com.jarvis.api.JarvisApi;
import com.jarvis.integrations.PluginManager;
import com.jarvis.integrations.google.AuthorizedGoogleClient;
import com.jarvis.integrations.google.GoogleAuth;
import com.jarvis.integrations.google.GoogleWorkspacePlugin;
import com.jarvis.integrations.llm.AnthropicPolicy;
import com.jarvis.integrations.llm.AnthropicPolicy.LlmTransport;
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
import java.util.stream.Collectors;

/**
 * Composition root. {@link #build} assembles the full runtime (API, hardware monitor, webcam vision
 * hook); {@link #buildApi} is the lighter path used by tests.
 */
final class AppWiring {

    static final String OFFLINE_HINT =
            "(offline mode - set the ANTHROPIC_API_KEY environment variable to enable AI) "
                    + "You said: ";

    /** Everything the launcher needs to run. */
    record Runtime(JarvisApi api, boolean online, String model,
            HardwareMonitor monitor, WebServer.VisionHook vision, boolean googleConnected) {
    }

    private AppWiring() {
    }

    /** Production runtime with durable memory in the user's home directory. */
    static Runtime build(String apiKey, String model) {
        Path memoryFile = Path.of(System.getProperty("user.home"), ".jarvis", "memory.tsv");
        MemoryStore<String> memory = new FileBackedStore(memoryFile);
        boolean online = isOnline(apiKey);

        ToolRegistry tools = new ToolRegistry();
        PluginManager plugins = new PluginManager(tools);
        plugins.install(new MarkToolsPlugin(memory));
        plugins.install(new SystemControlPlugin());

        GoogleAuth google = googleAuth(memory);
        boolean googleConnected = google != null && google.isConnected();
        if (googleConnected) {
            plugins.install(new GoogleWorkspacePlugin(new AuthorizedGoogleClient(google)));
        }

        WebServer.VisionHook visionHook = null;
        AgentPolicy policy;
        if (online) {
            LlmTransport apiTransport = AnthropicPolicy.anthropicTransport(apiKey);
            VisionTool vision = new VisionTool(apiTransport, model);
            tools.register(vision);
            visionHook = vision::analyze;
            policy = AnthropicPolicy.withApiKey(apiKey, model, tools)
                    .withMemoryContext(() -> recall(memory));
        } else {
            policy = context -> new Decision.Respond(OFFLINE_HINT + context.input());
        }

        JarvisApi api = assemble(policy, tools, memory);
        HardwareMonitor monitor = new HardwareMonitor();
        return new Runtime(api, online, model, monitor, visionHook, googleConnected);
    }

    /** Lighter wiring with an injectable store (tests). No monitor, no vision. */
    static JarvisApi buildApi(String apiKey, String model, MemoryStore<String> memory) {
        ToolRegistry tools = new ToolRegistry();
        PluginManager plugins = new PluginManager(tools);
        plugins.install(new MarkToolsPlugin(memory));
        plugins.install(new SystemControlPlugin());
        AgentPolicy policy = isOnline(apiKey)
                ? AnthropicPolicy.withApiKey(apiKey, model, tools).withMemoryContext(() -> recall(memory))
                : context -> new Decision.Respond(OFFLINE_HINT + context.input());
        return assemble(policy, tools, memory);
    }

    private static JarvisApi assemble(AgentPolicy policy, ToolRegistry tools, MemoryStore<String> memory) {
        PromptRouter<AgentPolicy> router = new PromptRouter<>(List.of());
        Planner planner = goal -> new Plan(goal, List.of(PlanStep.pending("goal", goal)));
        // Budget 6: a briefing legitimately chains clock + reminders + weather + news.
        Orchestrator orchestrator = new Orchestrator(router, policy, tools, planner, memory, 6);
        return new DefaultJarvisApi(orchestrator);
    }

    /** Collapses the durable "preferences" scope into a recall block for the system prompt. */
    static String recall(MemoryStore<String> memory) {
        List<String> facts = memory.query("preferences").stream()
                .map(e -> "- " + e.value())
                .collect(Collectors.toList());
        return facts.isEmpty() ? "" : String.join("\n", facts);
    }

    static boolean isOnline(String apiKey) {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Builds a {@link GoogleAuth} from env credentials, or null if they are not set. */
    static GoogleAuth googleAuth(MemoryStore<String> memory) {
        String id = System.getenv("GOOGLE_CLIENT_ID");
        String secret = System.getenv("GOOGLE_CLIENT_SECRET");
        if (id == null || id.isBlank() || secret == null || secret.isBlank()) {
            return null;
        }
        return new GoogleAuth(id, secret, memory);
    }

    /** Store used by the standalone {@code --connect-google} flow. */
    static MemoryStore<String> memoryStore() {
        return new FileBackedStore(Path.of(System.getProperty("user.home"), ".jarvis", "memory.tsv"));
    }
}
