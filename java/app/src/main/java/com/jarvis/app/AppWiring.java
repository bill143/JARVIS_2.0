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
import com.jarvis.kb.KnowledgeBase;
import com.jarvis.tasks.TaskBoard;
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
            UpdateChecker updates, LicenseManager license, UsageMeter usage, TaskBoard tasks,
            WorkflowService workflows, KnowledgeBase knowledge, MultiAgentService agents,
            AutonomousService autonomous, SemanticMemoryService semantic,
            DiscussionService discussion, ProviderSettingsService providers, BrainVault brain,
            SolicitationsService solicitations, UploadedDocsService uploads, McpService mcp,
            BrainManager chatBrain, OrchestrationService orchestration, GatedLaneService gatedLane) {

        /** The cross-cutting services the web layer exposes (governance, updates, licensing, usage). */
        Governance governance() {
            return new Governance(auditLog, pluginRegistry, permissions, permissionPolicy,
                    updates, license, usage, tasks, workflows, knowledge, agents, autonomous, semantic,
                    discussion, providers, brain, solicitations, uploads, mcp, chatBrain, orchestration,
                    gatedLane);
        }
    }

    /** Services the web server exposes: audit, tools, permissions, updates, licensing, usage, tasks. */
    record Governance(AuditLog auditLog, PluginRegistry plugins,
            PermissionBroker permissions, PermissionPolicy permissionPolicy, UpdateChecker updates,
            LicenseManager license, UsageMeter usage, TaskBoard tasks, WorkflowService workflows,
            KnowledgeBase knowledge, MultiAgentService agents, AutonomousService autonomous,
            SemanticMemoryService semantic, DiscussionService discussion,
            ProviderSettingsService providers, BrainVault brain, SolicitationsService solicitations,
            UploadedDocsService uploads, McpService mcp, BrainManager chatBrain,
            OrchestrationService orchestration, GatedLaneService gatedLane) {
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
        // Phase 5 reference integration. Dormant until JARVIS_GITHUB_TOKEN is set — the tools are
        // registered (and manifest-tiered) either way; a missing token yields a graceful error, not
        // a crash. MUTATING actions are gated by the permission layer via their manifest risk tier.
        plugins.install(new com.jarvis.integrations.github.GitHubPlugin(
                com.jarvis.integrations.github.HttpGitHubTransport.fromEnvironment()));
        // OpenHuman advisor (read-only). Dormant until 'openhuman-core serve' is running and
        // JARVIS_OPENHUMAN_URL + OPENHUMAN_CORE_TOKEN are set. Arm's-length HTTP only (GPL-safe).
        com.jarvis.integrations.openhuman.OpenHumanClient openhuman =
                new com.jarvis.integrations.openhuman.OpenHumanClient(
                        com.jarvis.integrations.openhuman.HttpOpenHumanTransport.fromEnvironment());
        plugins.install(new com.jarvis.integrations.openhuman.OpenHumanPlugin(openhuman));

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

        // Solicitations Command Center. Sources + document connectors are dormant-by-default (env
        // gated); the AI tools register here (before governance wraps the registry) so they are
        // manifest-tiered READ_ONLY. Every source query / open / refresh is audited by the service.
        SolicitationsService solicitations = buildSolicitations(auditLog);
        plugins.install(new SolicitationsPlugin(solicitations));

        ToolRegistry governedTools = governedRegistry(
                tools, pluginRegistry, auditLog, permissionPolicy, permissions);

        // Usage metering: every Claude call's token usage is recorded (durable, provider-agnostic).
        UsageMeter usageMeter = new UsageMeter(new FileRecordStore(
                Path.of(System.getProperty("user.home"), ".jarvis", "usage")), PriceTable.defaults());

        // Model provider selection (Settings → Models & Providers). An active configured provider
        // (e.g. NVIDIA / OpenAI / a local model) overrides the ANTHROPIC_API_KEY default; it takes
        // effect on (re)start. Keys live locally in the memory store and are never logged.
        ProviderSettingsService providerSettings = new ProviderSettingsService(memory);
        final ToolRegistry brainTools = governedTools;
        // Rebuilt on demand so activating a provider (APIs & Models) hot-swaps the brain, no restart.
        java.util.function.Supplier<AgentPolicy> brainFactory = () -> {
            java.util.Optional<ProviderSettingsService.Active> cp = providerSettings.active();
            if (cp.isPresent()) {
                ProviderSettingsService.Active a = cp.get();
                String m = a.model() == null || a.model().isBlank() ? model : a.model();
                com.jarvis.integrations.llm.LlmProvider prov = "anthropic".equals(a.kind())
                        ? new com.jarvis.integrations.llm.AnthropicProvider(
                                AnthropicPolicy.anthropicTransport(a.apiKey()))
                        : new com.jarvis.integrations.llm.OpenAiCompatibleProvider(
                                com.jarvis.integrations.llm.OpenAiCompatibleProvider.transport(
                                        a.baseUrl(), a.apiKey()));
                return AnthropicPolicy.withProvider(prov, m, 1024, brainTools)
                        .withMemoryContext(() -> recall(memory, people))
                        .withHistory(() -> conversationHistory(memory, "dashboard", 24))
                        .withUsageSink((usedModel, in, out) -> {
                            if (in > 0 || out > 0) {
                                usageMeter.record(a.name(), usedModel, in, out);
                            }
                        });
            }
            if (online) {
                return AnthropicPolicy.withApiKey(apiKey, model, brainTools)
                        .withMemoryContext(() -> recall(memory, people))
                        .withHistory(() -> conversationHistory(memory, "dashboard", 24))
                        .withUsageSink((usedModel, in, out) -> {
                            if (in > 0 || out > 0) {
                                usageMeter.record("anthropic", usedModel, in, out);
                            }
                        });
            }
            return context -> new Decision.Respond(OFFLINE_HINT + context.input());
        };
        boolean brainOnline = providerSettings.active().isPresent() || online;
        String effectiveModel = providerSettings.active()
                .map(ProviderSettingsService.Active::model).filter(m -> !m.isBlank()).orElse(model);

        SwappablePolicy swappable = new SwappablePolicy(brainFactory.get());
        JarvisApi api = assemble(swappable, governedTools, memory);
        BrainManager chatBrain = new BrainManager(swappable, brainFactory,
                () -> providerSettings.active().map(ProviderSettingsService.Active::model)
                        .filter(m -> !m.isBlank()).orElse(model));

        // Local-first multi-model orchestration (ensemble + hierarchy) over the configured providers.
        OrchestrationService orchestration =
                new OrchestrationService(providerSettings, auditLog, effectiveModel);

        // Policy-gated self-hosted lane (off by default, default-deny, audited). Responsible analogue
        // of the Hermes "Shadow CEO": local-only, absolute harm denylist, allowlist-scoped.
        GatedLaneService gatedLane =
                new GatedLaneService(memory, providerSettings, auditLog, effectiveModel);
        HardwareMonitor monitor = new HardwareMonitor();
        PeopleRecognizer recognizer = online
                ? new PeopleRecognizer(AnthropicPolicy.anthropicTransport(apiKey), model) : null;

        // Non-blocking startup update check (notify-only). Dormant unless JARVIS_UPDATE_URL is set.
        UpdateChecker updates = updateChecker();
        Thread updateThread = new Thread(updates::check, "jarvis-update-check");
        updateThread.setDaemon(true);
        updateThread.start();

        LicenseManager license = licenseManager();
        TaskBoard tasks = new TaskBoard(new FileRecordStore(
                Path.of(System.getProperty("user.home"), ".jarvis", "tasks")));
        WorkflowService workflows = new WorkflowService(
                new FileRecordStore(Path.of(System.getProperty("user.home"), ".jarvis", "workflows")),
                new FileRecordStore(Path.of(System.getProperty("user.home"), ".jarvis", "workflow-runs")),
                api, auditLog);
        workflows.startScheduler();
        KnowledgeBase knowledge = new KnowledgeBase(new FileRecordStore(
                Path.of(System.getProperty("user.home"), ".jarvis", "knowledge")));
        MultiAgentService agents = new MultiAgentService(api, auditLog);
        AutonomousService autonomous = new AutonomousService(api, auditLog);
        // Semantic memory: cloud embeddings dormant by default (decision D3) — keyword fallback until
        // JARVIS_EMBEDDINGS_KEY is set, at which point recall becomes meaning-based.
        SemanticMemoryService semantic = new SemanticMemoryService(
                new FileRecordStore(Path.of(System.getProperty("user.home"), ".jarvis", "semantic")),
                HttpEmbeddingProvider.fromEnvironment(), auditLog);
        // Project Discussion: JARVIS chairs, OpenHuman advises. Bounded + audited; read-only.
        DiscussionService discussion = new DiscussionService(api, openhuman, auditLog,
                new FileRecordStore(Path.of(System.getProperty("user.home"), ".jarvis", "discussions")));
        // BRAIN (Obsidian): read-only mirror of a local vault. Dormant unless OBSIDIAN_VAULT_PATH is
        // set; the memory backend stays the source of truth. No writes to the vault in Phase 1.
        BrainVault brain = BrainVault.fromEnvironment(auditLog);

        // Uploaded documents the assistant can read (txt/md/csv/json native, docx/xlsx JDK-only,
        // pdf via PDFBox). In-memory and session-scoped; every upload is audited.
        UploadedDocsService uploads = new UploadedDocsService(auditLog);

        // MCP connections: talk to Model Context Protocol servers over HTTP. Dormant until the user
        // adds one; configs persist locally (scope 'mcp'), tokens never returned/logged, all audited.
        McpService mcp = new McpService(auditLog);

        return new Runtime(api, brainOnline, effectiveModel, monitor, visionHook, googleConnected,
                googleService, memory, people, recognizer, auditLog, pluginRegistry, permissions,
                permissionPolicy, updates, license, usageMeter, tasks, workflows, knowledge, agents,
                autonomous, semantic, discussion, providerSettings, brain, solicitations, uploads, mcp,
                chatBrain, orchestration, gatedLane);
    }

    /**
     * Builds the solicitations service. SAM.gov is the live source when {@code SAMGOV_API_KEY} is set
     * (dormant otherwise); GovTribe is a dormant MCP-bridge seam (the runtime has no MCP client of its
     * own). Drive/OneDrive connectors are read-only and dormant until credentials + folder scope are
     * configured. No autonomous polling — the cache refreshes only on explicit request.
     */
    private static SolicitationsService buildSolicitations(AuditLog auditLog) {
        List<com.jarvis.solicitations.SolicitationSourceAdapter> sources = List.of(
                new com.jarvis.solicitations.SamGovAdapter(
                        com.jarvis.solicitations.HttpSamGovTransport.fromEnvironment()),
                com.jarvis.solicitations.GovTribeMcpAdapter.dormant());
        List<com.jarvis.solicitations.DocumentConnector> connectors = List.of(
                GoogleDriveConnector.fromEnvironment(null),
                OneDriveConnector.fromEnvironment(null));
        return new SolicitationsService(sources, connectors, auditLog);
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
        for (String resource : new String[] {
                "/manifests/builtin.json", "/manifests/github.json", "/manifests/openhuman.json",
                "/manifests/solicitations.json"}) {
            try (InputStream in = AppWiring.class.getResourceAsStream(resource)) {
                if (in != null) {
                    manifests.addAll(ManifestLoader.parseArray(
                            new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                }
            } catch (IOException e) {
                // A missing/unreadable manifest bundle -> those tools default to UNKNOWN risk.
            }
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
