package com.jarvis.app;

import com.jarvis.agent.loop.AgentPolicy;
import com.jarvis.agent.loop.Decision;
import com.jarvis.agent.orchestration.Orchestrator;
import com.jarvis.agent.routing.PromptRouter;
import com.jarvis.api.DefaultJarvisApi;
import com.jarvis.api.JarvisApi;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.audit.AuditedTool;
import com.jarvis.audit.RecordStoreAuditLog;
import com.jarvis.integrations.PluginManager;
import com.jarvis.registry.HealthTrackingTool;
import com.jarvis.registry.ManifestLoader;
import com.jarvis.registry.PluginRegistry;
import com.jarvis.registry.ToolManifest;
import com.jarvis.security.AuthorizingTool;
import com.jarvis.security.PermissionBroker;
import com.jarvis.security.PermissionLevel;
import com.jarvis.security.PermissionPolicy;
import com.jarvis.licensing.EncryptedLicenseStore;
import com.jarvis.licensing.LicenseManager;
import com.jarvis.licensing.LicenseVerifier;
import com.jarvis.metering.PriceTable;
import com.jarvis.metering.UsageMeter;
import com.jarvis.updater.HttpManifestSource;
import com.jarvis.updater.ManifestSource;
import com.jarvis.updater.ManifestVerifier;
import com.jarvis.updater.UpdateChecker;
import com.jarvis.updater.Version;
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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /** The running application version (kept in sync with packaging --app-version). */
    static final String APP_VERSION = "0.1.0";

    /** Everything the launcher needs to run. */
    record Runtime(JarvisApi api, boolean online, String model,
            HardwareMonitor monitor, WebServer.VisionHook vision, boolean googleConnected,
            com.jarvis.integrations.google.GoogleWorkspaceService googleService,
            MemoryStore<String> memory, PeopleStore people, PeopleRecognizer recognizer,
            AuditLog auditLog, PluginRegistry pluginRegistry,
            PermissionBroker permissions, PermissionPolicy permissionPolicy,
            UpdateChecker updates, LicenseManager license, UsageMeter usage) {

        /** The cross-cutting services the web layer exposes (governance, updates, licensing, usage). */
        Governance governance() {
            return new Governance(
                    auditLog, pluginRegistry, permissions, permissionPolicy, updates, license, usage);
        }
    }

    /** Services the web server exposes: audit, tools, permissions, updates, licensing, usage. */
    record Governance(AuditLog auditLog, PluginRegistry plugins,
            PermissionBroker permissions, PermissionPolicy permissionPolicy, UpdateChecker updates,
            LicenseManager license, UsageMeter usage) {
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

        // Governance: manifest-driven risk tiers, a durable audit log, and permission prompts.
        PluginRegistry pluginRegistry = pluginRegistry();
        AuditLog auditLog = new RecordStoreAuditLog(new FileRecordStore(
                Path.of(System.getProperty("user.home"), ".jarvis", "audit")));
        PermissionPolicy permissionPolicy = new PermissionPolicy();   // prompt on destructive
        PermissionBroker permissions = new PermissionBroker();
        ToolRegistry governedTools = governedRegistry(
                tools, pluginRegistry, auditLog, permissionPolicy, permissions);

        // Usage metering: every Claude call's token usage is recorded (durable, provider-agnostic).
        UsageMeter usageMeter = new UsageMeter(new FileRecordStore(
                Path.of(System.getProperty("user.home"), ".jarvis", "usage")), PriceTable.defaults());

        AgentPolicy policy = online
                ? AnthropicPolicy.withApiKey(apiKey, model, governedTools)
                        .withMemoryContext(() -> recall(memory, people))
                        .withHistory(() -> conversationHistory(memory, "dashboard", 24))
                        .withUsageSink((usedModel, in, out) -> {
                            if (in > 0 || out > 0) {
                                usageMeter.record("anthropic", usedModel, in, out);
                            }
                        })
                : context -> new Decision.Respond(OFFLINE_HINT + context.input());

        JarvisApi api = assemble(policy, governedTools, memory);
        HardwareMonitor monitor = new HardwareMonitor();
        PeopleRecognizer recognizer = online
                ? new PeopleRecognizer(AnthropicPolicy.anthropicTransport(apiKey), model) : null;

        // Non-blocking startup update check (notify-only). Dormant unless JARVIS_UPDATE_URL is set.
        UpdateChecker updates = updateChecker();
        Thread updateThread = new Thread(updates::check, "jarvis-update-check");
        updateThread.setDaemon(true);
        updateThread.start();

        LicenseManager license = licenseManager();

        return new Runtime(api, online, model, monitor, visionHook, googleConnected, googleService,
                memory, people, recognizer, auditLog, pluginRegistry, permissions, permissionPolicy,
                updates, license, usageMeter);
    }

    /**
     * Builds the license manager. Dormant by default: with no embedded public key
     * ({@code /license-public-key.b64}) it runs in DEV mode (unlocked). Ship that resource and
     * unlicensed installs present the locked activation state. The license is stored, encrypted, at
     * {@code ~/.jarvis/license.dat}.
     */
    static LicenseManager licenseManager() {
        EncryptedLicenseStore store = new EncryptedLicenseStore(
                Path.of(System.getProperty("user.home"), ".jarvis", "license.dat"));
        LicenseVerifier verifier = null;
        try (InputStream in = AppWiring.class.getResourceAsStream("/license-public-key.b64")) {
            if (in != null) {
                verifier = LicenseVerifier.fromBase64(
                        new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            verifier = null;   // no / bad key -> DEV mode (unlocked)
        }
        return new LicenseManager(verifier, store);
    }

    /**
     * Builds the update checker. Dormant by default: it only checks when {@code JARVIS_UPDATE_URL}
     * points at a hosted manifest, and only trusts one signed by the key in the optional resource
     * {@code /update-public-key.b64}. With neither, it reports DISABLED and never touches the network.
     */
    static UpdateChecker updateChecker() {
        String url = System.getenv("JARVIS_UPDATE_URL");
        ManifestSource source = (url == null || url.isBlank()) ? null : HttpManifestSource.of(url);
        ManifestVerifier verifier = null;
        try (InputStream in = AppWiring.class.getResourceAsStream("/update-public-key.b64")) {
            if (in != null) {
                verifier = ManifestVerifier.fromBase64(
                        new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            verifier = null;   // no / bad key -> manifests will be reported UNVERIFIED
        }
        return new UpdateChecker(Version.parse(APP_VERSION), source, verifier);
    }

    /**
     * Wraps every tool with the full governance stack: health tracking (feeds {@link PluginRegistry}),
     * audit recording at the tool's manifest {@link com.jarvis.tools.RiskTier}, and a permission gate
     * that confirms mutating/destructive actions. Layering (outermost first): authorize → audit →
     * health → raw, so a denied action is never executed and never counts as a health failure.
     */
    private static ToolRegistry governedRegistry(ToolRegistry raw, PluginRegistry registry,
            AuditLog auditLog, PermissionPolicy permissionPolicy, PermissionBroker permissions) {
        ToolRegistry governed = new ToolRegistry();
        for (Tool tool : raw.list()) {
            com.jarvis.tools.RiskTier tier = registry.riskTier(tool.name());
            Tool tracked = new HealthTrackingTool(tool, registry);
            Tool audited = new AuditedTool(tracked, auditLog, tier, AuditTrigger.USER);
            governed.register(new AuthorizingTool(audited, tier, permissionPolicy, permissions, auditLog));
        }
        return governed;
    }

    /** Loads built-in tool manifests (bundled resource) plus any user plugins in ~/.jarvis/plugins. */
    static PluginRegistry pluginRegistry() {
        List<ToolManifest> manifests = new ArrayList<>();
        try (InputStream in = AppWiring.class.getResourceAsStream("/manifests/builtin.json")) {
            if (in != null) {
                manifests.addAll(ManifestLoader.parseArray(
                        new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            // No bundled manifests -> tools default to UNKNOWN risk.
        }
        manifests.addAll(ManifestLoader.loadDirectory(
                Path.of(System.getProperty("user.home"), ".jarvis", "plugins")));
        return new PluginRegistry(manifests);
    }

    /** Lighter wiring with an injectable store (tests). No monitor, no vision. */
    static JarvisApi buildApi(String apiKey, String model, MemoryStore<String> memory) {
        ToolRegistry tools = new ToolRegistry();
        PluginManager plugins = new PluginManager(tools);
        plugins.install(new MarkToolsPlugin(memory));
        plugins.install(new SystemControlPlugin());
        ToolRegistry governedTools = governedRegistry(
                tools, pluginRegistry(), new RecordStoreAuditLog(new InMemoryRecordStore()),
                new PermissionPolicy(PermissionLevel.OFF), new PermissionBroker());
        AgentPolicy policy = isOnline(apiKey)
                ? AnthropicPolicy.withApiKey(apiKey, model, governedTools).withMemoryContext(() -> recall(memory))
                : context -> new Decision.Respond(OFFLINE_HINT + context.input());
        return assemble(policy, governedTools, memory);
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
