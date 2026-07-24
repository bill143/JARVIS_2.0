package com.jarvis.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.integrations.llm.LlmProvider;
import com.jarvis.memory.MemoryStore;
import com.jarvis.tools.RiskTier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The policy-gated local lane — the responsible, right-sized analogue of the Hermes "Shadow CEO".
 *
 * <p>It routes a task to a <b>self-hosted / local</b> model only when a strict gate approves, and it
 * is <b>off by default</b>. The gate is <b>default-deny</b>: a task is refused unless (1) the lane is
 * enabled, (2) a self-hosted lane provider is configured, (3) the task matches the operator's
 * allowlist, and (4) it does <b>not</b> match the absolute harm denylist — which always wins and can
 * never be overridden by the allowlist. Every gate decision and every lane invocation is written to
 * the {@link AuditLog}, and lane output is flagged as needing review before it is trusted.
 *
 * <p>This exists so domain tasks a commercial API declines for <em>scope</em> reasons (e.g. niche
 * regulatory/technical content) can run on a model the operator hosts — not to bypass safety. The
 * harm denylist is unconditional.
 */
final class GatedLaneService {

    static final String SCOPE = "gatedlane";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Unconditional harm categories — always denied, regardless of the allowlist. Package-visible
     * so {@link OpenHumanWriteGate} can apply the same absolute denylist to memory-write requests.
     */
    static final List<String> DENYLIST = List.of(
            "weapon", "explosive", "bomb", "malware", "ransomware", "exploit code", "bioweapon",
            "chemical weapon", "child sexual", "csam", "assassinat", "untraceable", "counterfeit",
            "credit card number", "how to kill", "poison someone");

    /** The lane's configuration, as shown to the operator (never includes keys). */
    record Config(boolean enabled, List<String> allow, String provider) {
    }

    /** A gate decision for a task. */
    record GateDecision(boolean approved, String reason) {
    }

    /** The result of attempting to run a task on the lane. */
    record RunResult(boolean approved, String reason, String output, String provider,
            boolean needsReview) {
    }

    private final MemoryStore<String> store;
    private final ProviderSettingsService providers;
    private final AuditLog audit;                       // nullable
    private final Function<ProviderSettingsService.Active, LlmProvider> providerFactory;
    private final String defaultModel;

    GatedLaneService(MemoryStore<String> store, ProviderSettingsService providers, AuditLog audit,
            String defaultModel) {
        this(store, providers, audit, defaultModel, OrchestrationService::build);
    }

    GatedLaneService(MemoryStore<String> store, ProviderSettingsService providers, AuditLog audit,
            String defaultModel, Function<ProviderSettingsService.Active, LlmProvider> providerFactory) {
        this.store = store;
        this.providers = providers;
        this.audit = audit;
        this.defaultModel = defaultModel == null || defaultModel.isBlank() ? "model" : defaultModel;
        this.providerFactory = providerFactory;
    }

    Config config() {
        JsonNode c = store.get(SCOPE, "config").map(e -> parse(e.value()))
                .orElse(MAPPER.createObjectNode());
        List<String> allow = new ArrayList<>();
        c.path("allow").forEach(n -> allow.add(n.asText()));
        return new Config(c.path("enabled").asBoolean(false), allow, c.path("provider").asText(""));
    }

    /** Persists the lane config. Enabling requires a configured self-hosted provider to take effect. */
    void setConfig(boolean enabled, List<String> allow, String provider) {
        ObjectNode c = MAPPER.createObjectNode();
        c.put("enabled", enabled);
        c.put("provider", provider == null ? "" : provider.strip());
        var arr = c.putArray("allow");
        if (allow != null) {
            for (String a : allow) {
                if (a != null && !a.isBlank()) {
                    arr.add(a.strip());
                }
            }
        }
        store.put(SCOPE, "config", c.toString());
        record("gatedlane_config", "enabled=" + enabled + " provider=" + c.path("provider").asText(""),
                AuditOutcome.SUCCESS);
    }

    /** Evaluates a task against the gate without running anything (the /gate/test path). */
    GateDecision evaluate(String task) {
        Config cfg = config();
        String t = task == null ? "" : task.toLowerCase();
        for (String bad : DENYLIST) {
            if (t.contains(bad)) {
                return new GateDecision(false, "blocked: matches an absolute harm category");
            }
        }
        if (!cfg.enabled()) {
            return new GateDecision(false, "the gated lane is disabled");
        }
        Optional<ProviderSettingsService.Active> lane = laneProvider(cfg);
        if (lane.isEmpty()) {
            return new GateDecision(false, "no self-hosted lane provider configured");
        }
        if (!isSelfHosted(lane.get().baseUrl())) {
            return new GateDecision(false, "lane provider must be a self-hosted/local endpoint");
        }
        if (cfg.allow().isEmpty()) {
            return new GateDecision(false, "default-deny: add an allowlist scope to permit tasks");
        }
        for (String term : cfg.allow()) {
            if (!term.isBlank() && t.contains(term.toLowerCase())) {
                return new GateDecision(true, "approved: matches allowlist scope '" + term + "'");
            }
        }
        return new GateDecision(false, "outside the configured allowlist");
    }

    /** Evaluates and, only if approved, runs the task on the self-hosted lane (audited). */
    RunResult run(String task) {
        GateDecision d = evaluate(task);
        record("gatedlane_gate", d.approved() ? "APPROVED" : ("REJECTED — " + d.reason()),
                d.approved() ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE);
        if (!d.approved()) {
            return new RunResult(false, d.reason(), "", "", false);
        }
        ProviderSettingsService.Active lane = laneProvider(config()).orElseThrow();
        String model = lane.model() == null || lane.model().isBlank() ? defaultModel : lane.model();
        String output;
        boolean ok;
        try {
            LlmProvider prov = providerFactory.apply(lane);
            output = prov.complete(new LlmProvider.Request(model,
                    "You are the operator's self-hosted domain model. Answer precisely.",
                    List.of(new LlmProvider.Message("user", task)), 1024)).text().strip();
            ok = true;
        } catch (Exception e) {
            output = "(lane error: " + (e.getMessage() == null ? e.toString() : e.getMessage()) + ")";
            ok = false;
        }
        record("gatedlane_run", lane.name() + "/" + model + (ok ? "" : " failed"),
                ok ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE);
        return new RunResult(true, d.reason(), output, lane.name(), true);
    }

    // ---- internals ----

    private Optional<ProviderSettingsService.Active> laneProvider(Config cfg) {
        if (cfg.provider().isBlank()) {
            return Optional.empty();
        }
        return providers.allConfigured().stream()
                .filter(a -> a.name().equals(cfg.provider())).findFirst();
    }

    /** A lane must point at a loopback / private-network host — never a public cloud endpoint. */
    static boolean isSelfHosted(String baseUrl) {
        if (baseUrl == null) {
            return false;
        }
        String u = baseUrl.toLowerCase();
        return u.contains("localhost") || u.contains("127.0.0.1") || u.contains("0.0.0.0")
                || u.contains("::1") || u.contains("192.168.") || u.contains("10.")
                || u.contains("172.16.") || u.contains(".local");
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private void record(String action, String detail, AuditOutcome outcome) {
        if (audit == null) {
            return;
        }
        audit.record(new AuditEvent(AuditCategory.TOOL_INVOCATION, action, AuditTrigger.USER,
                RiskTier.MUTATING, outcome, detail));
    }
}
