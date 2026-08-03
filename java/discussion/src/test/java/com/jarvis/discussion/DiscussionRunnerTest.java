package com.jarvis.discussion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.discussion.DiscussionRunner.Chair;
import com.jarvis.discussion.DiscussionRunner.ConsensusDiscussion;
import com.jarvis.discussion.DiscussionRunner.Round;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DiscussionRunnerTest {

    /** A chair that asks a fixed number of questions, then converges, and synthesizes a summary. */
    private static Chair chairAsking(int questions) {
        return new Chair() {
            @Override
            public String next(String topic, List<Round> soFar) {
                return soFar.size() < questions
                        ? "Q" + (soFar.size() + 1) + " about " + topic
                        : DiscussionRunner.CONVERGED_MARKER;
            }

            @Override
            public String synthesize(String topic, List<Round> rounds, boolean converged) {
                return "outcome after " + rounds.size() + " rounds, converged=" + converged;
            }
        };
    }

    @Test
    void runsUntilTheChairConvergesAndSynthesizes() {
        DiscussionRunner.Discussion d = new DiscussionRunner()
                .run("the go-kart budget", chairAsking(2), q -> "advisor says: " + q);
        assertEquals(2, d.rounds().size());
        assertTrue(d.converged());
        assertEquals("advisor says: Q1 about the go-kart budget", d.rounds().get(0).answer());
        assertTrue(d.outcome().contains("converged=true"));
    }

    @Test
    void neverExceedsTheHardRoundBudget() {
        // A chair that never converges is capped at MAX_ROUNDS.
        Chair neverDone = new Chair() {
            public String next(String topic, List<Round> soFar) {
                return "keep going";
            }

            public String synthesize(String topic, List<Round> rounds, boolean converged) {
                return "done";
            }
        };
        DiscussionRunner.Discussion d = new DiscussionRunner().run("x", neverDone, q -> "ok");
        assertEquals(DiscussionRunner.MAX_ROUNDS, d.rounds().size());
        assertFalse(d.converged());
    }

    @Test
    void anAdvisorFailureIsAnExplicitErrorRoundNotSilentSuccess() {
        DiscussionRunner.Discussion d = new DiscussionRunner().run("x", chairAsking(3), q -> {
            throw new RuntimeException("core offline");
        });
        assertEquals(1, d.rounds().size());
        assertTrue(d.rounds().get(0).failed());
        assertTrue(d.rounds().get(0).error().contains("core offline"));
        assertFalse(d.converged());   // a failed round never counts as convergence
    }

    @Test
    void theChairSeesTheTranscriptSoFar() {
        Chair counting = new Chair() {
            public String next(String topic, List<Round> soFar) {
                return soFar.size() >= 2 ? DiscussionRunner.CONVERGED_MARKER
                        : "round-" + soFar.size();
            }

            public String synthesize(String topic, List<Round> rounds, boolean converged) {
                return "n=" + rounds.size();
            }
        };
        DiscussionRunner.Discussion d = new DiscussionRunner().run("t", counting, q -> "a");
        assertEquals("n=2", d.outcome());
    }

    // ---- runWithConsensus() ---------------------------------------------------------------

    private static final Set<String> AGENTS = Set.of("chair", "advisor");

    private static ConsensusVote approve(String agent, int round) {
        return new ConsensusVote(agent, VoteDecision.APPROVE, "agreed", round);
    }

    private static ConsensusPolicy unanimous(int maxRounds) {
        return new ConsensusPolicy(ConsensusMode.UNANIMOUS, maxRounds, true, 0L); // 0 = no timeout
    }

    /** A chair that always asks another question — never converges on its own. */
    private static Chair neverConverging() {
        return new Chair() {
            public String next(String topic, List<Round> soFar) {
                return "round-" + soFar.size();
            }

            public String synthesize(String topic, List<Round> rounds, boolean converged) {
                return "outcome after " + rounds.size() + " rounds, converged=" + converged;
            }
        };
    }

    @Test
    void consensusOffDelegatesToLegacyRunByteForByte() {
        DiscussionRunner runner = new DiscussionRunner();
        DiscussionRunner.Discussion legacy = runner.run("x", chairAsking(2), q -> "a: " + q);
        ConsensusDiscussion wrapped = runner.runWithConsensus("x", chairAsking(2), q -> "a: " + q,
                ConsensusPolicy.off(), AGENTS, (agent, topic, soFar, round) -> approve(agent, round));

        assertEquals(legacy.rounds(), wrapped.discussion().rounds());
        assertEquals(legacy.outcome(), wrapped.discussion().outcome());
        assertEquals(legacy.converged(), wrapped.discussion().converged());
        assertTrue(wrapped.finalized());
        assertTrue(wrapped.consensus().achieved());
    }

    @Test
    void achievesConsensusAssoonAsBothAgentsApproveTheSameRound() {
        ConsensusDiscussion result = new DiscussionRunner().runWithConsensus("x", neverConverging(),
                q -> "answer to " + q, unanimous(3), AGENTS,
                (agent, topic, soFar, round) -> approve(agent, round));

        assertTrue(result.finalized());
        assertTrue(result.consensus().achieved());
        assertEquals(1, result.discussion().rounds().size()); // achieved on the very first round
    }

    @Test
    void keepsAskingUntilConsensusIsAchievedOrRoundsRunOut() {
        AtomicInteger callCount = new AtomicInteger();
        // Round 1: advisor rejects. Round 2: both approve.
        DiscussionRunner.VoteCaster caster = (agent, topic, soFar, round) -> {
            if (round == 1 && agent.equals("advisor")) {
                return new ConsensusVote(agent, VoteDecision.REJECT, "not yet", round);
            }
            return approve(agent, round);
        };
        ConsensusDiscussion result = new DiscussionRunner().runWithConsensus("x", neverConverging(),
                q -> { callCount.incrementAndGet(); return "a"; }, unanimous(3), AGENTS, caster);

        assertTrue(result.finalized());
        assertEquals(2, result.discussion().rounds().size());
        assertEquals(2, callCount.get());
    }

    @Test
    void failsClosedWhenTheRoundBudgetIsExhaustedWithoutAgreement() {
        DiscussionRunner.VoteCaster alwaysRejects = (agent, topic, soFar, round) ->
                new ConsensusVote(agent, VoteDecision.REJECT, "never satisfied", round);
        ConsensusDiscussion result = new DiscussionRunner().runWithConsensus("x", neverConverging(),
                q -> "a", unanimous(2), AGENTS, alwaysRejects);

        assertFalse(result.finalized());
        assertFalse(result.consensus().achieved());
        assertEquals(2, result.discussion().rounds().size()); // still ran the full policy budget
        assertFalse(result.discussion().converged());
        // The transcript/outcome are still populated for visibility, even though not finalized.
        assertFalse(result.discussion().outcome().isBlank());
    }

    @Test
    void policyMaxRoundsBoundsTheLoopNotJustTheHardCeiling() {
        AtomicInteger questionsAsked = new AtomicInteger();
        Chair counting = new Chair() {
            public String next(String topic, List<Round> soFar) {
                questionsAsked.incrementAndGet();
                return "q";
            }

            public String synthesize(String topic, List<Round> rounds, boolean converged) {
                return "done";
            }
        };
        DiscussionRunner.VoteCaster alwaysRejects = (agent, topic, soFar, round) ->
                new ConsensusVote(agent, VoteDecision.REJECT, "no", round);
        new DiscussionRunner().runWithConsensus("x", counting, q -> "a", unanimous(1), AGENTS,
                alwaysRejects);

        assertEquals(1, questionsAsked.get()); // policy.maxRounds()=1, well below MAX_ROUNDS=6
    }

    @Test
    void aVoteCasterFailureCountsAsAMissingVoteNotACrash() {
        DiscussionRunner.VoteCaster oneAgentAlwaysThrows = (agent, topic, soFar, round) -> {
            if (agent.equals("advisor")) {
                throw new RuntimeException("advisor vote endpoint offline");
            }
            return approve(agent, round);
        };
        ConsensusDiscussion result = new DiscussionRunner().runWithConsensus("x", neverConverging(),
                q -> "a", unanimous(2), AGENTS, oneAgentAlwaysThrows);

        assertFalse(result.finalized()); // "advisor" never successfully votes -> always blocking
        assertTrue(result.consensus().blockingAgents().contains("advisor"));
    }

    @Test
    void chairConvergingWithoutAnyAchievedVoteFailsClosed() {
        // The chair signals done on the very first call, before any question/vote round happens.
        Chair instantlyDone = new Chair() {
            public String next(String topic, List<Round> soFar) {
                return DiscussionRunner.CONVERGED_MARKER;
            }

            public String synthesize(String topic, List<Round> rounds, boolean converged) {
                return "n=" + rounds.size();
            }
        };
        ConsensusDiscussion result = new DiscussionRunner().runWithConsensus("x", instantlyDone,
                q -> "a", unanimous(3), AGENTS, (agent, topic, soFar, round) -> approve(agent, round));

        assertFalse(result.finalized());
        assertFalse(result.consensus().achieved());
        assertTrue(result.discussion().rounds().isEmpty());
    }

    @Test
    void aVoteCasterThatExceedsThePolicyTimeoutCountsAsMissing() {
        ConsensusPolicy shortTimeout = new ConsensusPolicy(ConsensusMode.UNANIMOUS, 1, true, 50L);
        DiscussionRunner.VoteCaster slowAdvisor = (agent, topic, soFar, round) -> {
            if (agent.equals("advisor")) {
                Thread.sleep(2000); // far beyond the 50ms policy timeout
            }
            return approve(agent, round);
        };
        ConsensusDiscussion result = new DiscussionRunner().runWithConsensus("x", neverConverging(),
                q -> "a", shortTimeout, AGENTS, slowAdvisor);

        assertFalse(result.finalized());
        assertTrue(result.consensus().blockingAgents().contains("advisor"));
    }
}
