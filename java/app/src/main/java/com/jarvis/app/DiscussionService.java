package com.jarvis.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.api.ChatRequest;
import com.jarvis.api.JarvisApi;
import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.discussion.DiscussionRunner;
import com.jarvis.integrations.openhuman.OpenHumanClient;
import com.jarvis.memory.RecordStore;
import com.jarvis.tools.RiskTier;
import java.util.List;
import java.util.Objects;

/**
 * App facade for "Project Discussion" mode: JARVIS <em>chairs</em> (via the governed
 * {@link JarvisApi#chat}) a bounded discussion with an advisor, then synthesizes an outcome. The
 * whole exchange is read-only — the advisor is consulted, never asked to act — and every advisor
 * turn is audited as an outbound consult. Transcripts persist to the append-only
 * {@link RecordStore} seam.
 *
 * <p><b>Who advises.</b> The OpenHuman core ({@link OpenHumanClient#consult}) is preferred when it
 * is configured and healthy. When it is not (the common case — the public OpenHuman ships no local
 * HTTP core), the advisor falls back to one of the user's configured model providers, preferring
 * one that is <em>not</em> the active chat brain so the chair gets a genuinely second opinion.
 * With neither available the feature stays dormant.
 *
 * <p>The {@link DiscussionRunner#MAX_ROUNDS} ceiling is the hard budget so two models can't talk
 * forever.
 */
final class DiscussionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ADVISOR_SYSTEM = "You are the ADVISOR in a bounded project"
            + " discussion chaired by another AI. Answer the chair's question directly and"
            + " concisely from your own knowledge and reasoning. Challenge weak assumptions."
            + " You are consulted for text only — you cannot run tools or take actions.";

    private final DiscussionRunner runner = new DiscussionRunner();
    private final JarvisApi api;
    private final OpenHumanClient advisor;            // nullable → OpenHuman path dormant
    private final AuditLog audit;                     // nullable
    private final RecordStore store;                  // nullable → not persisted
    private final ProviderSettingsService providers;  // nullable → no model fallback
    private final OrchestrationService orchestration; // nullable → no model fallback
    private final String collection;

    DiscussionService(JarvisApi api, OpenHumanClient advisor, AuditLog audit, RecordStore store) {
        this(api, advisor, audit, store, null, null);
    }

    DiscussionService(JarvisApi api, OpenHumanClient advisor, AuditLog audit, RecordStore store,
            ProviderSettingsService providers, OrchestrationService orchestration) {
        this.api = Objects.requireNonNull(api, "api");
        this.advisor = advisor;
        this.audit = audit;
        this.store = store;
        this.providers = providers;
        this.orchestration = orchestration;
        this.collection = "discussions";
    }

    /** Whether any advisor is available (OpenHuman core, else a fallback roster model). */
    boolean advisorAvailable() {
        return openHumanAvailable() || modelAdvisor().isPresent();
    }

    /** Where advice comes from right now: {@code openhuman}, {@code model:<name>}, or empty. */
    String advisorSource() {
        if (openHumanAvailable()) {
            return "openhuman";
        }
        return modelAdvisor().map(a -> "model:" + a.name()).orElse("");
    }

    private boolean openHumanAvailable() {
        return advisor != null && advisor.available();
    }

    /**
     * The roster model that stands in as advisor when OpenHuman is dormant: the first configured
     * provider that is not the active chat brain (a real second opinion), else the active one.
     */
    private java.util.Optional<ProviderSettingsService.Active> modelAdvisor() {
        if (providers == null || orchestration == null) {
            return java.util.Optional.empty();
        }
        List<ProviderSettingsService.Active> all = providers.allConfigured();
        if (all.isEmpty()) {
            return java.util.Optional.empty();
        }
        String activeName = providers.active().map(ProviderSettingsService.Active::name).orElse("");
        return all.stream().filter(a -> !a.name().equals(activeName)).findFirst()
                .or(() -> all.stream().findFirst());
    }

    /** Runs a bounded discussion on {@code topic} and returns the transcript + synthesized outcome. */
    DiscussionRunner.Discussion run(String topic) {
        DiscussionRunner.Chair chair = new DiscussionRunner.Chair() {
            @Override
            public String next(String topic, List<DiscussionRunner.Round> soFar) {
                String prompt = "You are chairing a project discussion with an external advisor.\n"
                        + "Topic: " + topic + "\n" + transcript(soFar)
                        + "\nDecide the SINGLE next question to ask the advisor to move toward a"
                        + " decision. If the discussion has reached a clear conclusion, reply with"
                        + " EXACTLY the token " + DiscussionRunner.CONVERGED_MARKER + ". Otherwise"
                        + " reply with ONLY the question.";
                return api.chat(new ChatRequest("discussion", prompt)).response();
            }

            @Override
            public String synthesize(String topic, List<DiscussionRunner.Round> rounds,
                    boolean converged) {
                String prompt = "Summarize the outcome of this project discussion into a concise"
                        + " decision/plan — key points and next steps. Do NOT take any action.\n"
                        + "Topic: " + topic + "\n" + transcript(rounds);
                return api.chat(new ChatRequest("discussion", prompt)).response();
            }
        };

        DiscussionRunner.Advisor advisorFn = question -> {
            if (openHumanAvailable()) {
                String reply = advisor.consult(question, topic);
                if (audit != null) {
                    audit.record(new AuditEvent(AuditCategory.EXTERNAL_API, "openhuman-consult",
                            AuditTrigger.AUTONOMOUS, RiskTier.READ_ONLY, AuditOutcome.SUCCESS,
                            "topic: " + topic));
                }
                return reply;
            }
            ProviderSettingsService.Active model = modelAdvisor().orElseThrow(
                    () -> new IllegalStateException("no advisor available — connect the OpenHuman"
                            + " core or add a model provider on the APIs & Models page"));
            OrchestrationService.ModelResult r = orchestration.callOne(model, ADVISOR_SYSTEM,
                    "Discussion topic: " + topic + "\n\nChair's question: " + question,
                    1024, "discussion:advisor");
            if (!r.ok()) {
                throw new IllegalStateException("model advisor '" + model.name() + "' failed: "
                        + (r.error().isBlank() ? "no reply" : r.error()));
            }
            if (audit != null) {
                audit.record(new AuditEvent(AuditCategory.EXTERNAL_API, "model-advisor-consult",
                        AuditTrigger.AUTONOMOUS, RiskTier.READ_ONLY, AuditOutcome.SUCCESS,
                        model.name() + " · topic: " + topic));
            }
            return r.text();
        };

        DiscussionRunner.Discussion discussion = runner.run(topic, chair, advisorFn);
        persist(discussion);
        return discussion;
    }

    private static String transcript(List<DiscussionRunner.Round> rounds) {
        if (rounds.isEmpty()) {
            return "(no exchanges yet)";
        }
        StringBuilder sb = new StringBuilder("Transcript so far:");
        for (DiscussionRunner.Round r : rounds) {
            sb.append("\nQ").append(r.index()).append(": ").append(r.question());
            sb.append("\nA").append(r.index()).append(": ")
                    .append(r.failed() ? "[error: " + r.error() + "]" : r.answer());
        }
        return sb.toString();
    }

    private void persist(DiscussionRunner.Discussion d) {
        if (store == null) {
            return;
        }
        ObjectNode e = MAPPER.createObjectNode();
        e.put("topic", d.topic());
        e.put("converged", d.converged());
        e.put("outcome", d.outcome());
        ArrayNode rounds = e.putArray("rounds");
        for (DiscussionRunner.Round r : d.rounds()) {
            ObjectNode ro = rounds.addObject();
            ro.put("index", r.index());
            ro.put("question", r.question());
            ro.put("answer", r.failed() ? null : r.answer());
            if (r.failed()) {
                ro.put("error", r.error());
            }
        }
        store.append(collection, e.toString());
    }

    /** Recent discussions (newest first), as JSON payload strings. */
    List<String> recent(int max) {
        if (store == null) {
            return List.of();
        }
        List<com.jarvis.memory.StoredRecord> all = store.tail(collection, Math.max(1, max));
        return all.stream().map(com.jarvis.memory.StoredRecord::payload)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toList(), list -> {
                            java.util.Collections.reverse(list);
                            return list;
                        }));
    }
}
