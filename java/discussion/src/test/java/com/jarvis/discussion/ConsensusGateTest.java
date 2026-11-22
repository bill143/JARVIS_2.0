package com.jarvis.discussion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ConsensusGateTest {

    private static final ConsensusGate GATE = new ConsensusGate();
    private static final ConsensusPolicy UNANIMOUS =
            new ConsensusPolicy(ConsensusMode.UNANIMOUS, 3, true, 5000L);
    private static final ConsensusPolicy UNANIMOUS_NO_RATIONALE =
            new ConsensusPolicy(ConsensusMode.UNANIMOUS, 3, false, 5000L);

    private static ConsensusVote approve(String agent, int round) {
        return new ConsensusVote(agent, VoteDecision.APPROVE, "looks good", round);
    }

    @Test
    void offModeAlwaysAchievesRegardlessOfVotes() {
        ConsensusResult r = GATE.evaluate(ConsensusPolicy.off(),
                List.of(new ConsensusVote("chair", VoteDecision.REJECT, "no", 1)),
                Set.of("chair", "advisor"), 1);
        assertTrue(r.achieved());
        assertTrue(r.blockingAgents().isEmpty());
    }

    @Test
    void unanimousApprovalFromEveryExpectedAgentAchievesConsensus() {
        List<ConsensusVote> votes = List.of(approve("chair", 1), approve("advisor", 1));
        ConsensusResult r = GATE.evaluate(UNANIMOUS, votes, Set.of("chair", "advisor"), 1);
        assertTrue(r.achieved());
        assertTrue(r.blockingAgents().isEmpty());
        assertEquals(2, r.votes().size());
    }

    @Test
    void aSingleRejectBlocksUnanimityEvenIfEveryoneElseApproves() {
        List<ConsensusVote> votes = List.of(
                approve("chair", 1), new ConsensusVote("advisor", VoteDecision.REJECT, "no", 1));
        ConsensusResult r = GATE.evaluate(UNANIMOUS, votes, Set.of("chair", "advisor"), 1);
        assertFalse(r.achieved());
        assertEquals(List.of("advisor"), r.blockingAgents());
    }

    @Test
    void anAbstainBlocksUnanimityJustLikeAReject() {
        List<ConsensusVote> votes = List.of(
                approve("chair", 1), new ConsensusVote("advisor", VoteDecision.ABSTAIN, "unsure", 1));
        ConsensusResult r = GATE.evaluate(UNANIMOUS, votes, Set.of("chair", "advisor"), 1);
        assertFalse(r.achieved());
        assertEquals(List.of("advisor"), r.blockingAgents());
    }

    @Test
    void aMissingVoteBlocksUnanimity() {
        List<ConsensusVote> votes = List.of(approve("chair", 1)); // advisor never voted
        ConsensusResult r = GATE.evaluate(UNANIMOUS, votes, Set.of("chair", "advisor"), 1);
        assertFalse(r.achieved());
        assertEquals(List.of("advisor"), r.blockingAgents());
        assertTrue(r.reason().contains("advisor"));
    }

    @Test
    void requireRationaleTreatsABlankRationaleApprovalAsBlocking() {
        List<ConsensusVote> votes = List.of(
                approve("chair", 1), new ConsensusVote("advisor", VoteDecision.APPROVE, "", 1));
        ConsensusResult r = GATE.evaluate(UNANIMOUS, votes, Set.of("chair", "advisor"), 1);
        assertFalse(r.achieved());
        assertEquals(List.of("advisor"), r.blockingAgents());
    }

    @Test
    void rationaleNotRequiredWhenPolicySaysSo() {
        List<ConsensusVote> votes = List.of(
                approve("chair", 1), new ConsensusVote("advisor", VoteDecision.APPROVE, "", 1));
        ConsensusResult r = GATE.evaluate(UNANIMOUS_NO_RATIONALE, votes, Set.of("chair", "advisor"), 1);
        assertTrue(r.achieved());
    }

    @Test
    void emptyExpectedAgentsIsNotALegitimateConsensus() {
        ConsensusResult r = GATE.evaluate(UNANIMOUS, List.of(), Set.of(), 1);
        assertFalse(r.achieved());
        assertTrue(r.blockingAgents().isEmpty());
        assertTrue(r.reason().contains("no expected agents"));
    }

    @Test
    void onlyTheCurrentRoundsVotesAreConsidered() {
        // Round 1 had a rejection, but round 2 (the one being evaluated) is unanimous — the stale
        // round-1 rejection must not leak forward and block round 2.
        List<ConsensusVote> votes = List.of(
                new ConsensusVote("chair", VoteDecision.REJECT, "no", 1),
                approve("chair", 2), approve("advisor", 2));
        ConsensusResult r = GATE.evaluate(UNANIMOUS, votes, Set.of("chair", "advisor"), 2);
        assertTrue(r.achieved());
        assertEquals(2, r.votes().size());
        assertTrue(r.votes().stream().allMatch(v -> v.round() == 2));
    }

    @Test
    void blockingAgentsAreSortedRegardlessOfTheInputSetsIterationOrder() {
        // A LinkedHashSet preserves insertion order — deliberately insert "zebra" before "alpha" so
        // a non-deterministic implementation would return them in insertion order, not sorted order.
        Set<String> expected = new LinkedHashSet<>(List.of("zebra", "alpha", "middle"));
        ConsensusResult r = GATE.evaluate(UNANIMOUS, List.of(), expected, 1); // nobody voted -> all block
        assertEquals(List.of("alpha", "middle", "zebra"), r.blockingAgents());
    }

    @Test
    void latestVoteForAnAgentInARoundWins() {
        // Same agent voted twice in the same round (e.g. a resubmission) — the later one governs.
        List<ConsensusVote> votes = List.of(
                new ConsensusVote("chair", VoteDecision.REJECT, "first thought", 1),
                approve("chair", 1), approve("advisor", 1));
        ConsensusResult r = GATE.evaluate(UNANIMOUS, votes, Set.of("chair", "advisor"), 1);
        assertTrue(r.achieved());
    }

    @Test
    void isDeterministicAndThreadSafeUnderConcurrentEvaluation() throws Exception {
        List<ConsensusVote> votes = List.of(approve("chair", 1), approve("advisor", 1));
        Set<String> expected = Set.of("chair", "advisor");
        try (ExecutorService ex = Executors.newFixedThreadPool(8)) {
            List<Future<ConsensusResult>> futures = IntStream.range(0, 200)
                    .mapToObj(i -> ex.submit(() -> GATE.evaluate(UNANIMOUS, votes, expected, 1)))
                    .collect(Collectors.toList());
            for (Future<ConsensusResult> f : futures) {
                ConsensusResult r = f.get();
                assertTrue(r.achieved());
                assertTrue(r.blockingAgents().isEmpty());
            }
        }
    }
}
