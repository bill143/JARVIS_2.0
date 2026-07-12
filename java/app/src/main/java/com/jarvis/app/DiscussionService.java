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
 * {@link JarvisApi#chat}) a bounded discussion with the OpenHuman advisor
 * ({@link OpenHumanClient#consult}), then synthesizes an outcome. The whole exchange is read-only —
 * the advisor is consulted, never asked to act — and every advisor turn is audited as an outbound
 * consult. Transcripts persist to the append-only {@link RecordStore} seam.
 *
 * <p>The {@link DiscussionRunner#MAX_ROUNDS} ceiling is the hard budget so two models can't talk
 * forever.
 */
final class DiscussionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DiscussionRunner runner = new DiscussionRunner();
    private final JarvisApi api;
    private final OpenHumanClient advisor;   // nullable → advisor dormant
    private final AuditLog audit;            // nullable
    private final RecordStore store;         // nullable → not persisted
    private final String collection;

    DiscussionService(JarvisApi api, OpenHumanClient advisor, AuditLog audit, RecordStore store) {
        this.api = Objects.requireNonNull(api, "api");
        this.advisor = advisor;
        this.audit = audit;
        this.store = store;
        this.collection = "discussions";
    }

    /** Whether the OpenHuman advisor is configured (dormant otherwise). */
    boolean advisorAvailable() {
        return advisor != null && advisor.available();
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
            if (advisor == null || !advisor.available()) {
                throw new IllegalStateException("OpenHuman advisor is not configured");
            }
            String reply = advisor.consult(question, topic);
            if (audit != null) {
                audit.record(new AuditEvent(AuditCategory.EXTERNAL_API, "openhuman-consult",
                        AuditTrigger.AUTONOMOUS, RiskTier.READ_ONLY, AuditOutcome.SUCCESS,
                        "topic: " + topic));
            }
            return reply;
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
