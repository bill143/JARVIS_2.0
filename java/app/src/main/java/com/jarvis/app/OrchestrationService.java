package com.jarvis.app;

import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.integrations.llm.AnthropicPolicy;
import com.jarvis.integrations.llm.AnthropicProvider;
import com.jarvis.integrations.llm.LlmProvider;
import com.jarvis.integrations.llm.OpenAiCompatibleProvider;
import com.jarvis.integrations.openhuman.OpenHumanClient;
import com.jarvis.integrations.openhuman.routing.AgentRoleRouteConfig;
import com.jarvis.integrations.openhuman.routing.FallbackRoute;
import com.jarvis.integrations.openhuman.routing.RouteDecision;
import com.jarvis.integrations.openhuman.routing.RouteSelector;
import com.jarvis.integrations.openhuman.routing.RouteTier;
import com.jarvis.tools.RiskTier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Runs the configured model providers together instead of one at a time — the local-first,
 * right-sized version of the Hermes hierarchy. Two modes:
 *
 * <ul>
 *   <li><b>Ensemble</b> — several models answer the <em>same</em> prompt concurrently (one virtual
 *       thread each) and the answers are fused (majority vote / a judge model picks the best /
 *       best-valid-of-N / show all).</li>
 *   <li><b>Hierarchy</b> — a Conductor decomposes the request, Workers run the sub-tasks
 *       concurrently, and the Conductor arbitrates the final answer (see {@code hierarchy}).</li>
 * </ul>
 *
 * <p>Every model call and fusion decision is written to the {@link AuditLog}, and each run returns a
 * per-model/tier trace so the UI can show which model did what. The provider factory is injected so
 * the logic is unit-tested with fake models offline.
 */
final class OrchestrationService {

    /** One model's contribution to a run — the unit the trace is built from. */
    record ModelResult(String provider, String model, String role, String stage, boolean ok,
            String text, long latencyMs, String error) {
    }

    /** The outcome of an ensemble run: the fused answer plus every model's result. */
    record EnsembleResult(String prompt, String fusion, String answer, String chosen,
            List<ModelResult> results) {
    }

    /** The outcome of a hierarchy run: the plan, the arbitrated answer, and the full tier trace. */
    record HierarchyResult(String prompt, List<String> plan, String answer, List<ModelResult> steps) {
    }

    /** The Tier-2 route target id used for the OpenHuman fallback candidate. */
    private static final String OPENHUMAN_TARGET_ID = "openhuman";

    private final ProviderSettingsService providers;
    private final AuditLog audit;                       // nullable
    private final Function<ProviderSettingsService.Active, LlmProvider> providerFactory;
    private final String defaultModel;
    private final RoutingSettings routingSettings;       // nullable — null means routing is inert
    private final OpenHumanClient openHuman;             // nullable — null means no Tier-2 target
    private final RouteSelector routeSelector;           // nullable — built once routingSettings is set

    OrchestrationService(ProviderSettingsService providers, AuditLog audit, String defaultModel) {
        this(providers, audit, defaultModel, OrchestrationService::build, null, null);
    }

    OrchestrationService(ProviderSettingsService providers, AuditLog audit, String defaultModel,
            Function<ProviderSettingsService.Active, LlmProvider> providerFactory) {
        this(providers, audit, defaultModel, providerFactory, null, null);
    }

    /**
     * Full constructor adding Tier-2 routing/failover. {@code routingSettings} and {@code openHuman}
     * may both be {@code null} to leave routing entirely inert — byte-for-byte the pre-Tier-2
     * behavior, which is exactly what the two convenience constructors above do (every existing
     * caller/test is unaffected). When both are supplied, routing only actually engages per call
     * once {@link RoutingSettings.Snapshot#openHumanEnabled()} reads {@code true} at that moment;
     * circuit breaker thresholds are captured once, from the snapshot at construction time.
     */
    OrchestrationService(ProviderSettingsService providers, AuditLog audit, String defaultModel,
            Function<ProviderSettingsService.Active, LlmProvider> providerFactory,
            RoutingSettings routingSettings, OpenHumanClient openHuman) {
        this.providers = providers;
        this.audit = audit;
        this.defaultModel = defaultModel == null || defaultModel.isBlank() ? "model" : defaultModel;
        this.providerFactory = providerFactory;
        this.routingSettings = routingSettings;
        this.openHuman = openHuman;
        this.routeSelector = routingSettings == null
                ? null : new RouteSelector(breakerConfig(routingSettings.snapshot()));
    }

    private static RouteSelector.BreakerConfig breakerConfig(RoutingSettings.Snapshot snap) {
        return new RouteSelector.BreakerConfig(
                snap.breakerFailThreshold(), snap.breakerWindowSec(), snap.breakerCooldownSec());
    }

    /** Builds the real LLM provider for a configured entry (openai-compatible or native Anthropic). */
    static LlmProvider build(ProviderSettingsService.Active a) {
        return "anthropic".equals(a.kind())
                ? new AnthropicProvider(AnthropicPolicy.anthropicTransport(a.apiKey()))
                : new OpenAiCompatibleProvider(
                        OpenAiCompatibleProvider.transport(a.baseUrl(), a.apiKey()));
    }

    /** Which providers an ensemble uses: the named set, else all with a role, else all configured. */
    List<ProviderSettingsService.Active> ensembleMembers(List<String> names) {
        List<ProviderSettingsService.Active> all = providers.allConfigured();
        if (names != null && !names.isEmpty()) {
            return all.stream().filter(a -> names.contains(a.name())).toList();
        }
        List<ProviderSettingsService.Active> roled =
                all.stream().filter(a -> !providers.roleOf(a.name()).isBlank()).toList();
        return roled.isEmpty() ? all : roled;
    }

    /**
     * Runs {@code prompt} across the chosen providers concurrently and fuses the answers.
     *
     * @param fusion one of {@code judge}, {@code vote}, {@code best}, {@code concat}; blank picks
     *     {@code judge} when a Conductor is assigned, otherwise {@code best}
     */
    EnsembleResult ensemble(String prompt, String fusion, List<String> names) {
        List<ProviderSettingsService.Active> members = ensembleMembers(names);
        if (members.isEmpty()) {
            return new EnsembleResult(prompt, fusion, "No models configured — add one on the APIs & "
                    + "Models page, sir.", "", List.of());
        }
        List<ModelResult> results = runConcurrently(members, "You are one of several expert models "
                + "answering in parallel. Answer the user directly and concisely.", prompt, "ensemble");
        List<ModelResult> okResults = results.stream().filter(ModelResult::ok).toList();

        String method = fusion == null || fusion.isBlank()
                ? (hasConductor() ? "judge" : "best") : fusion;
        String answer;
        String chosen = "";
        if (okResults.isEmpty()) {
            answer = "Every model failed to answer — check the keys on the APIs page (Test each one).";
            method = "none";
        } else {
            switch (method) {
                case "vote" -> {
                    ModelResult v = majorityVote(okResults);
                    answer = v.text();
                    chosen = v.provider() + " (majority)";
                }
                case "concat" -> {
                    StringBuilder sb = new StringBuilder();
                    for (ModelResult r : okResults) {
                        sb.append("### ").append(r.provider()).append(" (").append(r.model())
                                .append(")\n").append(r.text()).append("\n\n");
                    }
                    answer = sb.toString().strip();
                    chosen = "all " + okResults.size();
                }
                case "judge" -> {
                    ModelResult judged = judge(prompt, okResults);
                    answer = judged.text();
                    chosen = judged.provider();
                    results = new ArrayList<>(results);
                    results.add(judged);
                }
                default -> {
                    ModelResult best = okResults.get(0);
                    answer = best.text();
                    chosen = best.provider();
                }
            }
        }
        record("orchestrate_ensemble", members.size() + " models · fusion=" + method + " · chosen="
                + chosen, okResults.isEmpty() ? AuditOutcome.FAILURE : AuditOutcome.SUCCESS);
        return new EnsembleResult(prompt, method, answer, chosen, results);
    }

    /**
     * Hierarchy mode: a Conductor decomposes {@code prompt} into sub-tasks, Workers run them
     * concurrently, and the Conductor arbitrates a final answer. Falls back to a plain ensemble when
     * no Conductor is assigned.
     */
    HierarchyResult hierarchy(String prompt) {
        List<ProviderSettingsService.Active> conductors = providers.withRole("conductor");
        if (conductors.isEmpty()) {
            EnsembleResult e = ensemble(prompt, "", null);
            return new HierarchyResult(prompt,
                    List.of("(no Conductor assigned — ran a flat ensemble instead)"),
                    e.answer(), e.results());
        }
        ProviderSettingsService.Active conductor = conductors.get(0);
        List<ModelResult> steps = new ArrayList<>();

        // 1) Decompose.
        ModelResult plan = callOne(conductor, "You are the Conductor. Break the user's request into "
                + "2 to 5 concrete, independent sub-tasks. Reply with ONE sub-task per line, no "
                + "numbering, nothing else.", prompt, 512, "decompose");
        steps.add(plan);
        List<String> subtasks = new ArrayList<>();
        if (plan.ok()) {
            for (String line : plan.text().split("\n")) {
                String s = line.replaceFirst("^\\s*[-*\\d.\\)]+\\s*", "").strip();
                if (!s.isBlank()) {
                    subtasks.add(s);
                }
                if (subtasks.size() >= 5) {
                    break;
                }
            }
        }
        if (subtasks.isEmpty()) {
            subtasks.add(prompt);   // decomposition failed → treat the whole thing as one task
        }

        // 2) Workers run the sub-tasks concurrently (fall back through the tiers for the pool).
        List<ProviderSettingsService.Active> workers = providers.withRole("worker");
        if (workers.isEmpty()) {
            workers = providers.withRole("orchestrator");
        }
        if (workers.isEmpty()) {
            workers = List.of(conductor);
        }
        List<ProviderSettingsService.Active> assign = new ArrayList<>();
        List<String> tasks = new ArrayList<>();
        for (int i = 0; i < subtasks.size(); i++) {
            assign.add(workers.get(i % workers.size()));
            tasks.add(subtasks.get(i));
        }
        List<ModelResult> workResults = new ArrayList<>();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ModelResult>> fs = new ArrayList<>();
            for (int i = 0; i < assign.size(); i++) {
                String task = tasks.get(i);
                ProviderSettingsService.Active w = assign.get(i);
                fs.add(ex.submit(() -> callOne(w, "You are a Worker. Complete just this one sub-task "
                        + "precisely and concisely.", task, 800, "work")));
            }
            for (Future<ModelResult> f : fs) {
                try {
                    workResults.add(f.get());
                } catch (Exception ignored) {
                    // dead worker → skip
                }
            }
        }
        steps.addAll(workResults);

        // 3) Conductor arbitrates a final answer from the sub-results.
        StringBuilder synth = new StringBuilder("Original request:\n").append(prompt)
                .append("\n\nSub-task results:\n\n");
        for (int i = 0; i < workResults.size(); i++) {
            synth.append("[").append(tasks.get(i)).append("]\n")
                    .append(workResults.get(i).ok() ? workResults.get(i).text() : "(failed)")
                    .append("\n\n");
        }
        ModelResult finalAnswer = callOne(conductor, "You are the Conductor. Compose the single best "
                + "final answer for the user from the sub-task results. Reply with ONLY that answer.",
                synth.toString(), 1400, "arbitrate");
        steps.add(finalAnswer);

        String answer = finalAnswer.ok() && !finalAnswer.text().isBlank()
                ? finalAnswer.text()
                : workResults.stream().filter(ModelResult::ok).map(ModelResult::text)
                        .findFirst().orElse("The hierarchy could not produce an answer — check the "
                                + "Conductor/Worker keys on the APIs page.");
        record("orchestrate_hierarchy", subtasks.size() + " sub-tasks across "
                + workers.size() + " worker(s)", finalAnswer.ok() ? AuditOutcome.SUCCESS
                : AuditOutcome.FAILURE);
        return new HierarchyResult(prompt, subtasks, answer, steps);
    }

    // ---- internals ----

    boolean hasConductor() {
        return !providers.withRole("conductor").isEmpty();
    }

    List<ModelResult> runConcurrently(List<ProviderSettingsService.Active> members, String system,
            String prompt, String stage) {
        List<ModelResult> results = new ArrayList<>();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ModelResult>> futures = new ArrayList<>();
            for (ProviderSettingsService.Active a : members) {
                futures.add(ex.submit(() -> callOne(a, system, prompt, 1024, stage)));
            }
            for (Future<ModelResult> f : futures) {
                try {
                    results.add(f.get());
                } catch (Exception e) {
                    // A dead task still leaves the others; record nothing extra.
                }
            }
        }
        return results;
    }

    /**
     * The single model-invocation point — shared by {@link #runConcurrently} and {@link #hierarchy}.
     * Tier-2 routing is intercepted here, immediately before the Tier-1 call: when a
     * {@link RouteSelector} is wired AND {@link RoutingSettings.Snapshot#openHumanEnabled()} reads
     * {@code true} at this moment, the call goes through {@link #callOneRouted}; otherwise it takes
     * the exact original path via {@link #callOnePrimary}, so the {@code JARVIS_OPENHUMAN_ENABLED=
     * false} (default) behavior is byte-for-byte unchanged from before Tier-2 existed.
     */
    ModelResult callOne(ProviderSettingsService.Active a, String system, String prompt,
            int maxTokens, String stage) {
        if (routeSelector != null && routingSettings != null
                && routingSettings.snapshot().openHumanEnabled()) {
            return callOneRouted(a, system, prompt, maxTokens, stage);
        }
        return callOnePrimary(a, system, prompt, maxTokens, stage);
    }

    /** The pre-Tier-2 call path, unchanged — the legacy baseline the flag-off tests pin down. */
    private ModelResult callOnePrimary(ProviderSettingsService.Active a, String system, String prompt,
            int maxTokens, String stage) {
        String model = a.model() == null || a.model().isBlank() ? defaultModel : a.model();
        String role = providers.roleOf(a.name());
        long t0 = System.nanoTime();
        try {
            LlmProvider prov = providerFactory.apply(a);
            LlmProvider.Result r = prov.complete(new LlmProvider.Request(model, system,
                    List.of(new LlmProvider.Message("user", prompt)), maxTokens));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            record("orchestrate_call", a.name() + "/" + model + " (" + stage + ")", AuditOutcome.SUCCESS);
            return new ModelResult(a.name(), model, role, stage, true, r.text().strip(), ms, "");
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            record("orchestrate_call", a.name() + "/" + model + " failed (" + stage + ")",
                    AuditOutcome.FAILURE);
            return new ModelResult(a.name(), model, role, stage, false, "", ms, msg);
        }
    }

    /**
     * Builds a Tier-1(primary)+Tier-2(OpenHuman) route for {@code a}'s role, runs it through
     * {@link #routeSelector}, and emits one {@code route_decision} audit event covering the whole
     * decision. The OpenHuman fallback is only included when failover is enabled, at least one retry
     * is configured, and the client reports itself reachable/configured.
     */
    private ModelResult callOneRouted(ProviderSettingsService.Active a, String system, String prompt,
            int maxTokens, String stage) {
        RoutingSettings.Snapshot snap = routingSettings.snapshot();
        String role = providers.roleOf(a.name());
        List<FallbackRoute> chain = new ArrayList<>();
        chain.add(new FallbackRoute(0, a.name(), RouteTier.TIER1_PRIMARY));
        boolean includeFallback = snap.failoverEnabled() && snap.maxRetries() >= 1
                && openHuman != null && openHuman.available();
        if (includeFallback) {
            chain.add(new FallbackRoute(1, OPENHUMAN_TARGET_ID, RouteTier.TIER2_OPENHUMAN));
        }
        AgentRoleRouteConfig routeConfig = new AgentRoleRouteConfig(
                role.isBlank() ? "unassigned" : role, snap.failoverEnabled(), chain,
                snap.timeoutMs(), snap.maxRetries());

        // RouteSelector only returns routing metadata — this map carries the actual ModelResult
        // (text/latency/error) each attempted candidate produced, keyed by target id.
        Map<String, ModelResult> attempted = new ConcurrentHashMap<>();
        RouteSelector.RouteExecutor executor = (targetId, tier) -> {
            ModelResult r = tier == RouteTier.TIER2_OPENHUMAN
                    ? callOpenHuman(system, prompt, snap.timeoutMs())
                    : callOnePrimaryBounded(a, system, prompt, maxTokens, stage, snap.timeoutMs());
            attempted.put(targetId, r);
            return r.ok() ? RouteSelector.AttemptOutcome.ok(r.latencyMs())
                    : RouteSelector.AttemptOutcome.failed(classify(r.error()), r.latencyMs());
        };

        String correlationId = UUID.randomUUID().toString().substring(0, 8);
        RouteDecision decision = routeSelector.select(routeConfig, executor);
        recordRoutingDecision(decision, correlationId);

        ModelResult chosen = attempted.get(decision.selectedTarget());
        if (chosen != null) {
            return chosen;
        }
        // Nothing was actually attempted for the selected target (e.g. NO_ROUTE_CONFIGURED, or the
        // sole candidate's breaker was OPEN) — synthesize a failure result carrying the reason.
        String model = a.model() == null || a.model().isBlank() ? defaultModel : a.model();
        return new ModelResult(a.name(), model, role, stage, false, "",
                Math.max(decision.latencyMs(), 0), "routing: " + decision.reasonCode());
    }

    /** {@link #callOnePrimary}, but bounded by {@code timeoutMs} and mapping a timeout explicitly. */
    private ModelResult callOnePrimaryBounded(ProviderSettingsService.Active a, String system,
            String prompt, int maxTokens, String stage, int timeoutMs) {
        String model = a.model() == null || a.model().isBlank() ? defaultModel : a.model();
        String role = providers.roleOf(a.name());
        long t0 = System.nanoTime();
        try {
            LlmProvider prov = providerFactory.apply(a);
            LlmProvider.Result r = runWithTimeout(() -> prov.complete(new LlmProvider.Request(model,
                    system, List.of(new LlmProvider.Message("user", prompt)), maxTokens)), timeoutMs);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            record("orchestrate_call", a.name() + "/" + model + " (" + stage + ")", AuditOutcome.SUCCESS);
            return new ModelResult(a.name(), model, role, stage, true, r.text().strip(), ms, "");
        } catch (TimeoutException te) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            record("orchestrate_call", a.name() + "/" + model + " failed (" + stage + ")",
                    AuditOutcome.FAILURE);
            return new ModelResult(a.name(), model, role, stage, false, "", ms,
                    "timeout after " + timeoutMs + "ms");
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            record("orchestrate_call", a.name() + "/" + model + " failed (" + stage + ")",
                    AuditOutcome.FAILURE);
            return new ModelResult(a.name(), model, role, stage, false, "", ms, msg);
        }
    }

    /** Consults OpenHuman as the Tier-2 target, bounded by {@code timeoutMs}. */
    private ModelResult callOpenHuman(String system, String prompt, int timeoutMs) {
        long t0 = System.nanoTime();
        try {
            String reply = runWithTimeout(() -> openHuman.consult(prompt, system), timeoutMs);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            record("orchestrate_call", OPENHUMAN_TARGET_ID + " (consult)", AuditOutcome.SUCCESS);
            return new ModelResult(OPENHUMAN_TARGET_ID, OPENHUMAN_TARGET_ID, "", "consult", true,
                    reply.strip(), ms, "");
        } catch (TimeoutException te) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            record("orchestrate_call", OPENHUMAN_TARGET_ID + " failed (consult)", AuditOutcome.FAILURE);
            return new ModelResult(OPENHUMAN_TARGET_ID, OPENHUMAN_TARGET_ID, "", "consult", false, "",
                    ms, "timeout after " + timeoutMs + "ms");
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            record("orchestrate_call", OPENHUMAN_TARGET_ID + " failed (consult)", AuditOutcome.FAILURE);
            return new ModelResult(OPENHUMAN_TARGET_ID, OPENHUMAN_TARGET_ID, "", "consult", false, "",
                    ms, msg);
        }
    }

    /** Runs {@code call} on a fresh virtual thread, bounded by {@code timeoutMs}. */
    private static <T> T runWithTimeout(Callable<T> call, int timeoutMs) throws Exception {
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<T> f = ex.submit(call);
            try {
                return f.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                f.cancel(true);
                throw te;
            }
        }
    }

    /** Maps a failure message to a {@link RouteSelector.FailureKind} by the signals it contains. */
    private static RouteSelector.FailureKind classify(String message) {
        if (message == null || message.isBlank()) {
            return RouteSelector.FailureKind.OTHER;
        }
        String m = message.toLowerCase();
        if (m.contains("timeout") || m.contains("timed out")) {
            return RouteSelector.FailureKind.TIMEOUT;
        }
        if (m.contains("429")) {
            return RouteSelector.FailureKind.RATE_LIMITED;
        }
        if (m.matches(".*\\b5\\d\\d\\b.*")) {
            return RouteSelector.FailureKind.SERVER_ERROR;
        }
        if (m.contains("malformed") || m.contains("unexpected") || m.contains("parse")) {
            return RouteSelector.FailureKind.MALFORMED_RESPONSE;
        }
        return RouteSelector.FailureKind.OTHER;
    }

    /**
     * Emits one audit event per routing decision: role, tier, target, reason code, latency, and a
     * correlation id — never the prompt/response text or any credential, so nothing sensitive
     * reaches the log by construction.
     */
    private void recordRoutingDecision(RouteDecision d, String correlationId) {
        if (audit == null) {
            return;
        }
        String detail = "role=" + d.role() + " tier=" + (d.selectedTier() == null ? "none" : d.selectedTier())
                + " target=" + d.selectedTarget() + " reason=" + d.reasonCode()
                + " latencyMs=" + d.latencyMs() + " corr=" + correlationId;
        audit.record(new AuditEvent(AuditCategory.EXTERNAL_API, "route_decision", AuditTrigger.SYSTEM,
                RiskTier.READ_ONLY, d.success() ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE, detail));
    }

    /** Groups answers by normalized text and returns a representative of the largest group. */
    private static ModelResult majorityVote(List<ModelResult> results) {
        Map<String, List<ModelResult>> groups = new HashMap<>();
        for (ModelResult r : results) {
            groups.computeIfAbsent(normalize(r.text()), k -> new ArrayList<>()).add(r);
        }
        return groups.values().stream().max((a, b) -> Integer.compare(a.size(), b.size()))
                .map(g -> g.get(0)).orElse(results.get(0));
    }

    /** Asks a Conductor model to pick/merge the best answer; falls back to the first result. */
    private ModelResult judge(String prompt, List<ModelResult> candidates) {
        List<ProviderSettingsService.Active> conductors = providers.withRole("conductor");
        StringBuilder cand = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            cand.append("[Answer ").append(i + 1).append(" — ").append(candidates.get(i).provider())
                    .append("]\n").append(candidates.get(i).text()).append("\n\n");
        }
        String judgePrompt = "The user asked:\n" + prompt + "\n\nHere are candidate answers from "
                + "different models:\n\n" + cand + "Choose the single best, most correct answer. You "
                + "may merge their strengths. Reply with ONLY the final answer for the user.";
        if (!conductors.isEmpty()) {
            ModelResult j = callOne(conductors.get(0), "You are the arbiter (Conductor). Select and "
                    + "return the best final answer.", judgePrompt, 1200, "arbitrate");
            if (j.ok() && !j.text().isBlank()) {
                return new ModelResult(j.provider(), j.model(), "conductor", "arbitrate", true,
                        j.text(), j.latencyMs(), "");
            }
        }
        return candidates.get(0);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.strip().toLowerCase().replaceAll("\\s+", " ");
    }

    private void record(String action, String detail, AuditOutcome outcome) {
        if (audit == null) {
            return;
        }
        audit.record(new AuditEvent(AuditCategory.TOOL_INVOCATION, action, AuditTrigger.USER,
                RiskTier.READ_ONLY, outcome, detail));
    }
}
