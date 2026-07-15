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
import com.jarvis.tools.RiskTier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    private final ProviderSettingsService providers;
    private final AuditLog audit;                       // nullable
    private final Function<ProviderSettingsService.Active, LlmProvider> providerFactory;
    private final String defaultModel;

    OrchestrationService(ProviderSettingsService providers, AuditLog audit, String defaultModel) {
        this(providers, audit, defaultModel, OrchestrationService::build);
    }

    OrchestrationService(ProviderSettingsService providers, AuditLog audit, String defaultModel,
            Function<ProviderSettingsService.Active, LlmProvider> providerFactory) {
        this.providers = providers;
        this.audit = audit;
        this.defaultModel = defaultModel == null || defaultModel.isBlank() ? "model" : defaultModel;
        this.providerFactory = providerFactory;
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

    ModelResult callOne(ProviderSettingsService.Active a, String system, String prompt,
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
