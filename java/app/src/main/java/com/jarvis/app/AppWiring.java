package com.jarvis.app;

import com.jarvis.agent.loop.AgentPolicy;
import com.jarvis.agent.loop.Decision;
import com.jarvis.agent.orchestration.Orchestrator;
import com.jarvis.agent.routing.PromptRouter;
import com.jarvis.api.DefaultJarvisApi;
import com.jarvis.api.JarvisApi;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditedTool;
import com.jarvis.audit.RecordStoreAuditLog;
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
import com.jarvis.memory.FileRecordStore;
import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.memory.MemoryStore;
import com.jarvis.planning.Plan;
import com.jarvis.planning.PlanStep;
import com.jarvis.planning.Planner;
import com.jarvis.tools.Tool;
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
            HardwareMonitor monitor, WebServer.VisionHook vision, boolean googleConnected,
            com.jarvis.integrations.google.GoogleWorkspaceService googleService,
            MemoryStore<String> memory, PeopleStore people, PeopleRecognizer recognizer,
            AuditLog auditLog) {
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
        com.jarvis.integrations.google.GoogleWorkspaceService googleService = null;
        if (googleConnected) {
            googleService = new com.jarvis.integrations.google.GoogleWorkspaceService(
                    new AuthorizedGoogleClient(google));
            plugins.install(new GoogleWorkspacePlugin(googleService));
        }

        PeopleStore people = new PeopleStore(
                Path.of(System.getProperty("user.home"), ".jarvis", "people.json"));

        WebServer.VisionHook visionHook = null;
        if (online) {
            LlmTransport apiTransport = AnthropicPolicy.anthropicTransport(apiKey);
            VisionTool vision = new VisionTool(apiTransport, model);
            tools.register(vision);
            visionHook = vision::analyze;
        }

        // Governance: every tool call is recorded to a durable audit log (~/.jarvis/audit).
        AuditLog auditLog = new RecordStoreAuditLog(new FileRecordStore(
                Path.of(System.getProperty("user.home"), ".jarvis", "audit")));
        ToolRegistry auditedTools = auditRegistry(tools, auditLog);

        AgentPolicy policy = online
                ? AnthropicPolicy.withApiKey(apiKey, model, auditedTools)
                        .withMemoryContext(() -> recall(memory, people))
                        .withHistory(() -> conversationHistory(memory, "dashboard", 24))
                : context -> new Decision.Respond(OFFLINE_HINT + context.input());

        JarvisApi api = assemble(policy, auditedTools, memory);
        HardwareMonitor monitor = new HardwareMonitor();
        PeopleRecognizer recognizer = online
                ? new PeopleRecognizer(AnthropicPolicy.anthropicTransport(apiKey), model) : null;
        return new Runtime(api, online, model, monitor, visionHook, googleConnected, googleService,
                memory, people, recognizer, auditLog);
    }

    /** Wraps every tool in {@code raw} with an {@link AuditedTool} into a fresh registry. */
    private static ToolRegistry auditRegistry(ToolRegistry raw, AuditLog auditLog) {
        ToolRegistry audited = new ToolRegistry();
        for (Tool tool : raw.list()) {
            audited.register(new AuditedTool(tool, auditLog));
        }
        return audited;
    }

    /** Lighter wiring with an injectable store (tests). No monitor, no vision. */
    static JarvisApi buildApi(String apiKey, String model, MemoryStore<String> memory) {
        ToolRegistry tools = new ToolRegistry();
        PluginManager plugins = new PluginManager(tools);
        plugins.install(new MarkToolsPlugin(memory));
        plugins.install(new SystemControlPlugin());
        ToolRegistry auditedTools = auditRegistry(
                tools, new RecordStoreAuditLog(new InMemoryRecordStore()));
        AgentPolicy policy = isOnline(apiKey)
                ? AnthropicPolicy.withApiKey(apiKey, model, auditedTools).withMemoryContext(() -> recall(memory))
                : context -> new Decision.Respond(OFFLINE_HINT + context.input());
        return assemble(policy, auditedTools, memory);
    }

    private static JarvisApi assemble(AgentPolicy policy, ToolRegistry tools, MemoryStore<String> memory) {
        PromptRouter<AgentPolicy> router = new PromptRouter<>(List.of());
        Planner planner = goal -> new Plan(goal, List.of(PlanStep.pending("goal", goal)));
        // Budget 8: a full briefing chains clock + reminders + email + news + weather.
        Orchestrator orchestrator = new Orchestrator(router, policy, tools, planner, memory, 8);
        return new DefaultJarvisApi(orchestrator);
    }

    /** Collapses durable preferences + directions into a recall block (no people contacts). */
    static String recall(MemoryStore<String> memory) {
        List<String> lines = memory.query("preferences").stream()
                .map(e -> "- " + e.value())
                .collect(Collectors.toList());
        memory.get("instructions", "mail").map(m -> m.value()).filter(s -> !s.isBlank())
                .ifPresent(s -> lines.add("- Email handling directions: " + s));
        memory.get("instructions", "calendar").map(m -> m.value()).filter(s -> !s.isBlank())
                .ifPresent(s -> lines.add("- Calendar handling directions: " + s));
        memory.get("about", "me").map(m -> m.value()).filter(s -> !s.isBlank())
                .ifPresent(s -> lines.add("- About the user: " + s));
        return lines.isEmpty() ? "" : String.join("\n", lines);
    }

    /** Recall including the People directory, so JARVIS can email/contact people by name. */
    static String recall(MemoryStore<String> memory, PeopleStore people) {
        String base = recall(memory);
        String contacts = people.contactsBlock();
        if (contacts.isBlank()) {
            return base;
        }
        String directory = "Known people / contacts (use these to email or reach them by name):\n"
                + contacts;
        return base.isBlank() ? directory : base + "\n" + directory;
    }

    static boolean isOnline(String apiKey) {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Reads the last {@code maxMessages} conversation messages for {@code sessionId}, in order. */
    static java.util.List<AnthropicPolicy.ChatMessage> conversationHistory(
            MemoryStore<String> memory, String sessionId, int maxMessages) {
        String scope = com.jarvis.agent.orchestration.Orchestrator.conversationScope(sessionId);
        java.util.List<AnthropicPolicy.ChatMessage> all = memory.query(scope).stream()
                .sorted(java.util.Comparator.comparing(e -> e.key()))
                .map(e -> {
                    int tab = e.value().indexOf('\t');
                    String role = tab < 0 ? "user" : e.value().substring(0, tab);
                    String text = tab < 0 ? e.value() : e.value().substring(tab + 1);
                    return new AnthropicPolicy.ChatMessage(role, text);
                })
                .collect(Collectors.toList());
        int from = Math.max(0, all.size() - maxMessages);
        return all.subList(from, all.size());
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
