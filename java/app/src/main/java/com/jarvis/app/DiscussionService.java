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
import com.jarvis.discussion.ConsensusPolicy;
import com.jarvis.discussion.ConsensusVote;
import com.jarvis.discussion.DiscussionRunner;
import com.jarvis.integrations.openhuman.OpenHumanClient;
import com.jarvis.memory.RecordStore;
import com.jarvis.tools.RiskTier;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

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

    /** The fixed agent id the chair votes under in a consensus-gated discussion. */
    static final String CHAIR_AGENT_ID = "chair";

    private final DiscussionRunner runner = new DiscussionRunner();
    private final JarvisApi api;
    private final OpenHumanClient advisor;            // nullable → OpenHuman path dormant
    private final AuditLog audit;                     // nullable
    private final RecordStore store;                  // nullable → not persisted
    private final ProviderSettingsService providers;  // nullable → no model fallback
    private final OrchestrationService orchestration; // nullable → no model fallback
    private final ConsensusSettings consensusSettings; // nullable → consensus entirely inert
    private final String collection;

    DiscussionService(JarvisApi api, OpenHumanClient advisor, AuditLog audit, RecordStore store) {
        this(api, advisor, audit, store, null, null);
    }

    DiscussionService(JarvisApi api, OpenHumanClient advisor, AuditLog audit, RecordStore store,
            ProviderSettingsService providers, OrchestrationService orchestration) {
        this(api, advisor, audit, store, providers, orchestration, null);
    }

    /**
     * Full constructor adding optional consensus-gating. {@code consensusSettings} may be
     * {@code null} — every constructor above delegates with {@code null} — in which case
     * {@link #runWithConsensus} always behaves as {@link ConsensusPolicy#off()} regardless of any
     * request override, matching {@link ConsensusSettings#effectivePolicy}'s own default-deny rule.
     */
    DiscussionService(JarvisApi api, OpenHumanClient advisor, AuditLog audit, RecordStore store,
            ProviderSettingsService providers, OrchestrationService orchestration,
            ConsensusSettings consensusSettings) {
        this.api = Objects.requireNonNull(api, "api");
        this.advisor = advisor;
        this.audit = audit;
        this.store = store;
        this.providers = providers;
        this.orchestration = orchestration;
        this.consensusSettings = consensusSettings;
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
     * The current global consensus configuration — a safe, disabled default if no
     * {@link ConsensusSettings} was wired at all. Used by the {@code /discussion/run} HTTP handler to
     * layer a partial per-request override on top of the live global values, rather than resetting
     * unspecified fields to hardcoded defaults.
     */
    ConsensusSettings.Snapshot consensusSnapshot() {
        return consensusSettings == null
                ? new ConsensusSettings.Snapshot(false, com.jarvis.discussion.ConsensusMode.OFF,
                        ConsensusSettings.DEFAULT_MAX_ROUNDS, ConsensusSettings.DEFAULT_REQUIRE_RATIONALE,
                        ConsensusSettings.DEFAULT_TIMEOUT_MS)
                : consensusSettings.snapshot();
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
        DiscussionRunner.Discussion discussion =
                runner.run(topic, buildChair(false), buildAdvisor(topic));
        persist(discussion);
        return discussion;
    }

    /**
     * Runs a consensus-gated discussion on {@code topic}. {@code requestOverride} (nullable) is a
     * per-request policy that only takes effect when consensus is enabled globally — see
     * {@link ConsensusSettings#effectivePolicy}. When the effective policy is
     * {@link com.jarvis.discussion.ConsensusMode#OFF} (globally disabled, or no
     * {@code ConsensusSettings} was wired at all), this reproduces {@link #run} exactly, wrapped.
     */
    DiscussionRunner.ConsensusDiscussion runWithConsensus(String topic, ConsensusPolicy requestOverride) {
        ConsensusPolicy policy = consensusSettings == null
                ? ConsensusPolicy.off() : consensusSettings.effectivePolicy(requestOverride);
        String advisorAgentId = advisorAgentId();
        Set<String> expectedAgents = Set.of(CHAIR_AGENT_ID, advisorAgentId);
        boolean consensusActive = policy.mode() != com.jarvis.discussion.ConsensusMode.OFF;

        DiscussionRunner.ConsensusDiscussion result = runner.runWithConsensus(topic,
                buildChair(consensusActive), buildAdvisor(topic), policy, expectedAgents,
                buildVoteCaster(topic, advisorAgentId));
        persist(result.discussion());

        if (policy.mode() != com.jarvis.discussion.ConsensusMode.OFF) {
            if (result.consensus().achieved()) {
                record("CONSENSUS_REACHED", "topic: " + topic + " · " + result.consensus().reason());
            } else {
                record("CONSENSUS_FAILED", "topic: " + topic + " · " + result.consensus().reason());
                record("CONSENSUS_BLOCKED_ACTION",
                        "topic: " + topic + " · outcome computed but NOT finalized (fail-closed)");
            }
        }
        return result;
    }

    /**
     * @param consensusRoundAudit when {@code true} (only for {@link #runWithConsensus} with an
     *     actually-active policy), emits {@code CONSENSUS_ROUND_STARTED} per round. Legacy
     *     {@link #run} passes {@code false} so it never emits an audit event it didn't before —
     *     required for 100%-identical legacy behavior when consensus is off/unwired.
     */
    private DiscussionRunner.Chair buildChair(boolean consensusRoundAudit) {
        return new DiscussionRunner.Chair() {
            @Override
            public String next(String topic, List<DiscussionRunner.Round> soFar) {
                if (consensusRoundAudit) {
                    record("CONSENSUS_ROUND_STARTED", "topic: " + topic + " round=" + (soFar.size() + 1));
                }
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
    }

    private DiscussionRunner.Advisor buildAdvisor(String topic) {
        return question -> {
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
    }

    /** Where advice comes from, as an agent id — {@code advisorSource()} but never blank. */
    private String advisorAgentId() {
        String source = advisorSource();
        return source.isBlank() ? "advisor" : source;
    }

    /**
     * Asks {@code agentId} (the chair, via {@code api.chat}; the advisor, via the same channel
     * {@link #buildAdvisor} uses) to vote on the discussion as of {@code soFar}, in a fixed
     * {@code DECISION: <APPROVE|REJECT|ABSTAIN>} / {@code RATIONALE: <text>} format. An unparseable
     * reply becomes an explicit {@code ABSTAIN} — never silently treated as approval.
     */
    private DiscussionRunner.VoteCaster buildVoteCaster(String topic, String advisorAgentId) {
        return (agentId, voteTopic, soFar, round) -> {
            String prompt = "Vote on whether this discussion has reached a satisfactory conclusion.\n"
                    + "Topic: " + voteTopic + "\n" + transcript(soFar)
                    + "\nReply in EXACTLY this format, nothing else:\n"
                    + "DECISION: APPROVE, REJECT, or ABSTAIN\n"
                    + "RATIONALE: one short sentence";
            String reply = agentId.equals(CHAIR_AGENT_ID)
                    ? api.chat(new ChatRequest("discussion", prompt)).response()
                    : consultAdvisorForVote(prompt);
            ConsensusVote vote = parseVote(agentId, reply, round);
            record("CONSENSUS_VOTE_RECORDED",
                    "topic: " + topic + " agent=" + agentId + " decision=" + vote.decision()
                            + " round=" + round);
            return vote;
        };
    }

    private String consultAdvisorForVote(String prompt) throws Exception {
        if (openHumanAvailable()) {
            return advisor.consult(prompt, "");
        }
        ProviderSettingsService.Active model = modelAdvisor().orElseThrow(
                () -> new IllegalStateException("no advisor available to vote"));
        OrchestrationService.ModelResult r =
                orchestration.callOne(model, ADVISOR_SYSTEM, prompt, 256, "discussion:vote");
        if (!r.ok()) {
            throw new IllegalStateException("model advisor vote failed: " + r.error());
        }
        return r.text();
    }

    /** Parses the fixed DECISION/RATIONALE vote format; unparseable input becomes ABSTAIN. */
    private static ConsensusVote parseVote(String agentId, String reply, int round) {
        String text = reply == null ? "" : reply;
        com.jarvis.discussion.VoteDecision decision = com.jarvis.discussion.VoteDecision.ABSTAIN;
        String rationale = "";
        for (String line : text.split("\n")) {
            String stripped = line.strip();
            String upper = stripped.toUpperCase(Locale.ROOT);
            if (upper.startsWith("DECISION:")) {
                String value = stripped.substring("DECISION:".length()).strip().toUpperCase(Locale.ROOT);
                if (value.contains("APPROVE")) {
                    decision = com.jarvis.discussion.VoteDecision.APPROVE;
                } else if (value.contains("REJECT")) {
                    decision = com.jarvis.discussion.VoteDecision.REJECT;
                } else {
                    decision = com.jarvis.discussion.VoteDecision.ABSTAIN;
                }
            } else if (upper.startsWith("RATIONALE:")) {
                rationale = stripped.substring("RATIONALE:".length()).strip();
            }
        }
        return new ConsensusVote(agentId, decision, rationale, round);
    }

    private void record(String action, String detail) {
        if (audit == null) {
            return;
        }
        audit.record(new AuditEvent(AuditCategory.SYSTEM, action, AuditTrigger.AUTONOMOUS,
                RiskTier.READ_ONLY, AuditOutcome.SUCCESS, detail));
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
