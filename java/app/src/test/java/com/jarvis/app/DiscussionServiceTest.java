package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.api.ChatRequest;
import com.jarvis.api.ChatResponse;
import com.jarvis.api.JarvisApi;
import com.jarvis.api.PlanRequest;
import com.jarvis.api.PlanResponse;
import com.jarvis.audit.AuditEntry;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.RecordStoreAuditLog;
import com.jarvis.discussion.ConsensusMode;
import com.jarvis.discussion.ConsensusPolicy;
import com.jarvis.discussion.DiscussionRunner;
import com.jarvis.integrations.llm.LlmProvider;
import com.jarvis.integrations.openhuman.OpenHumanClient;
import com.jarvis.integrations.openhuman.OpenHumanResponse;
import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.memory.InMemoryStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiscussionServiceTest {

    private static JarvisApi echoApi() {
        return AppWiring.buildApi(null, "m", new InMemoryStore<>());
    }

    /** Providers with an active "Chair" brain and a second, non-active "SecondOpinion" model. */
    private static ProviderSettingsService twoProviders() {
        ProviderSettingsService p = new ProviderSettingsService(new InMemoryStore<>(),
                (b, k) -> java.util.List.of());
        p.save("Chair", "openai", "https://x/v1", "k1", "m-chair", true);
        p.save("SecondOpinion", "openai", "https://x/v1", "k2", "m-second", false);
        return p;
    }

    private static OrchestrationService fakeOrchestration(ProviderSettingsService p) {
        return new OrchestrationService(p, null, "m",
                a -> req -> new LlmProvider.Result("advice from " + a.name(), 1, 1));
    }

    // ---- advisor fallback (regression: feature dormant without an OpenHuman core) ----

    @Test
    void advisorFallsBackToARosterModelWhenOpenHumanIsAbsent() {
        ProviderSettingsService p = twoProviders();
        DiscussionService svc = new DiscussionService(echoApi(), null, null, null,
                p, fakeOrchestration(p));
        assertTrue(svc.advisorAvailable());
        // Prefers the provider that is NOT the active chat brain — a real second opinion.
        assertEquals("model:SecondOpinion", svc.advisorSource());
    }

    @Test
    void singleProviderStillAdvisesEvenIfItIsTheActiveBrain() {
        ProviderSettingsService p = new ProviderSettingsService(new InMemoryStore<>(),
                (b, k) -> java.util.List.of());
        p.save("Only", "openai", "https://x/v1", "k", "m", true);
        DiscussionService svc = new DiscussionService(echoApi(), null, null, null,
                p, fakeOrchestration(p));
        assertTrue(svc.advisorAvailable());
        assertEquals("model:Only", svc.advisorSource());
    }

    @Test
    void discussionRunsWithTheModelAdvisor() {
        ProviderSettingsService p = twoProviders();
        DiscussionService svc = new DiscussionService(echoApi(), null, null, null,
                p, fakeOrchestration(p));
        DiscussionRunner.Discussion d = svc.run("Should we bid the riverfront project?");
        assertFalse(d.rounds().isEmpty());
        DiscussionRunner.Round first = d.rounds().get(0);
        assertFalse(first.failed());
        assertEquals("advice from SecondOpinion", first.answer());
        assertFalse(d.outcome().isBlank());   // chair synthesized an outcome
    }

    @Test
    void dormantWithNoAdvisorAndNoProviders() {
        DiscussionService svc = new DiscussionService(echoApi(), null, null, null);
        assertFalse(svc.advisorAvailable());
        assertEquals("", svc.advisorSource());
        // A run still terminates: the advisor failure becomes an explicit error round.
        DiscussionRunner.Discussion d = svc.run("anything");
        assertFalse(d.rounds().isEmpty());
        assertTrue(d.rounds().get(0).failed());
        assertFalse(d.converged());
    }

    @Test
    void openHumanTakesPriorityWhenHealthy() {
        // A fake OpenHuman core that answers /health with 200 → available()==true.
        com.jarvis.integrations.openhuman.OpenHumanTransport healthy =
                (method, path, body) -> new OpenHumanResponse(200, "{}");
        OpenHumanClient openhuman = new OpenHumanClient(healthy);
        ProviderSettingsService p = twoProviders();
        DiscussionService svc = new DiscussionService(echoApi(), openhuman, null, null,
                p, fakeOrchestration(p));
        assertTrue(svc.advisorAvailable());
        assertEquals("openhuman", svc.advisorSource());   // core outranks the model fallback
    }

    // ---- consensus: runWithConsensus() ---------------------------------------------------

    /** A fake chair-side JarvisApi that scripts replies by inspecting the prompt shape. */
    private static JarvisApi scriptedChairApi(String chairVoteDecision) {
        return new JarvisApi() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                String p = request.prompt();
                String text;
                if (p.contains("Vote on whether")) {
                    text = "DECISION: " + chairVoteDecision + "\nRATIONALE: because";
                } else if (p.contains("Decide the SINGLE next question")) {
                    text = "What is the budget?"; // never self-converges — the gate decides
                } else {
                    text = "outcome synthesized";
                }
                return new ChatResponse(request.sessionId(), true, text, 0);
            }

            @Override
            public PlanResponse plan(PlanRequest request) {
                throw new UnsupportedOperationException("not used by discussions");
            }
        };
    }

    /** A fake model advisor that scripts its vote reply the same way the chair fake does. */
    private static OrchestrationService votingOrchestration(ProviderSettingsService p,
            String advisorVoteDecision) {
        return new OrchestrationService(p, null, "m", a -> req -> {
            String content = req.messages().get(0).content();
            String text = content.contains("Vote on whether")
                    ? "DECISION: " + advisorVoteDecision + "\nRATIONALE: because"
                    : "advice from " + a.name();
            return new LlmProvider.Result(text, 1, 1);
        });
    }

    private static ConsensusSettings enabledConsensus(int maxRounds) {
        return new ConsensusSettings(new ConnectorSettingsService(new InMemoryStore<>(), Map.of(
                "JARVIS_CONSENSUS_ENABLED", "true",
                "JARVIS_CONSENSUS_MODE", "UNANIMOUS",
                "JARVIS_CONSENSUS_MAX_ROUNDS", String.valueOf(maxRounds))::get));
    }

    private static ConsensusSettings disabledConsensus() {
        return new ConsensusSettings(
                new ConnectorSettingsService(new InMemoryStore<>(), Map.<String, String>of()::get));
    }

    @Test
    void withNoConsensusSettingsWiredRunWithConsensusMatchesLegacyRunExactly() {
        ProviderSettingsService p = twoProviders();
        DiscussionService svc = new DiscussionService(scriptedChairApi("APPROVE"), null, null, null,
                p, votingOrchestration(p, "APPROVE")); // 6-arg ctor -> consensusSettings == null

        DiscussionRunner.ConsensusDiscussion result = svc.runWithConsensus("topic", null);

        assertTrue(result.finalized());
        assertTrue(result.consensus().achieved());
        assertEquals("consensus disabled", result.consensus().reason());
    }

    @Test
    void requestOverrideIsIgnoredWhenConsensusIsGloballyDisabled() {
        ProviderSettingsService p = twoProviders();
        DiscussionService svc = new DiscussionService(scriptedChairApi("REJECT"), null, null, null,
                p, votingOrchestration(p, "REJECT"), disabledConsensus());

        // Even though the request explicitly asks for UNANIMOUS, the global switch is off, so this
        // must behave exactly like consensus-disabled (default-deny), never attempting REJECT votes.
        ConsensusPolicy attemptedOverride = new ConsensusPolicy(ConsensusMode.UNANIMOUS, 3, true, 0L);
        DiscussionRunner.ConsensusDiscussion result = svc.runWithConsensus("topic", attemptedOverride);

        assertTrue(result.finalized());
        assertEquals("consensus disabled", result.consensus().reason());
    }

    @Test
    void unanimousConsensusAchievedWhenGloballyEnabledAndBothApprove() {
        ProviderSettingsService p = twoProviders();
        AuditLog audit = new RecordStoreAuditLog(new InMemoryRecordStore());
        DiscussionService svc = new DiscussionService(scriptedChairApi("APPROVE"), null, audit, null,
                p, votingOrchestration(p, "APPROVE"), enabledConsensus(3));

        DiscussionRunner.ConsensusDiscussion result = svc.runWithConsensus("go-kart budget", null);

        assertTrue(result.finalized());
        assertTrue(result.consensus().achieved());

        List<AuditEntry> events = audit.recent(50);
        assertTrue(events.stream().anyMatch(e -> e.event().action().equals("CONSENSUS_ROUND_STARTED")));
        assertTrue(events.stream().anyMatch(e -> e.event().action().equals("CONSENSUS_VOTE_RECORDED")
                && e.event().detail().contains("chair") && e.event().detail().contains("APPROVE")));
        assertTrue(events.stream().anyMatch(e -> e.event().action().equals("CONSENSUS_REACHED")));
        assertFalse(events.stream().anyMatch(e -> e.event().action().equals("CONSENSUS_FAILED")));
    }

    @Test
    void consensusFailsClosedAndAuditsFailureWhenTheAdvisorNeverApproves() {
        ProviderSettingsService p = twoProviders();
        AuditLog audit = new RecordStoreAuditLog(new InMemoryRecordStore());
        DiscussionService svc = new DiscussionService(scriptedChairApi("APPROVE"), null, audit, null,
                p, votingOrchestration(p, "REJECT"), enabledConsensus(1)); // 1 round, advisor rejects

        DiscussionRunner.ConsensusDiscussion result = svc.runWithConsensus("go-kart budget", null);

        assertFalse(result.finalized());
        assertFalse(result.consensus().achieved());
        assertTrue(result.consensus().blockingAgents().stream().anyMatch(a -> a.contains("SecondOpinion")));

        List<AuditEntry> events = audit.recent(50);
        assertTrue(events.stream().anyMatch(e -> e.event().action().equals("CONSENSUS_FAILED")));
        assertTrue(events.stream().anyMatch(e -> e.event().action().equals("CONSENSUS_BLOCKED_ACTION")));
    }

    @Test
    void legacyRunNeverEmitsAnyConsensusAuditEvents() {
        // Regression guard for the bug caught during development: buildChair() is shared by both
        // run() and runWithConsensus() — legacy run() must never emit CONSENSUS_* events.
        ProviderSettingsService p = twoProviders();
        AuditLog audit = new RecordStoreAuditLog(new InMemoryRecordStore());
        DiscussionService svc = new DiscussionService(scriptedChairApi("APPROVE"), null, audit, null,
                p, votingOrchestration(p, "APPROVE"), enabledConsensus(3));

        svc.run("plain legacy discussion");

        List<AuditEntry> events = audit.recent(50);
        assertFalse(events.stream().anyMatch(e -> e.event().action().startsWith("CONSENSUS_")));
    }
}
