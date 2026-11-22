package com.jarvis.discussion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure, deterministic evaluation of whether a discussion round has reached consensus. No network
 * calls, no clock, no mutable state — every input is a value, every output is a value, and the same
 * inputs always produce the same output regardless of call order or thread.
 */
public final class ConsensusGate {

    /**
     * Evaluates {@code votes} (only those matching {@code currentRound} are considered) against
     * {@code policy} for {@code expectedAgentIds}.
     *
     * <p>{@link ConsensusMode#OFF} always returns {@code achieved=true} — there is nothing to gate.
     * {@link ConsensusMode#UNANIMOUS} requires every expected agent to have cast an
     * {@link VoteDecision#APPROVE} vote in {@code currentRound}: a {@code REJECT}, an
     * {@code ABSTAIN}, a missing vote, or (when {@link ConsensusPolicy#requireRationale()} is set)
     * an {@code APPROVE} with a blank rationale all block unanimity and name that agent in
     * {@link ConsensusResult#blockingAgents()}. That list is always returned in sorted (alphabetical)
     * order, independent of the iteration order of the {@code expectedAgentIds} set passed in, so the
     * result is deterministic regardless of which {@link Set} implementation the caller uses.
     */
    public ConsensusResult evaluate(ConsensusPolicy policy, List<ConsensusVote> votes,
            Set<String> expectedAgentIds, int currentRound) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(votes, "votes");
        Objects.requireNonNull(expectedAgentIds, "expectedAgentIds");

        List<ConsensusVote> roundVotes = votes.stream()
                .filter(v -> v.round() == currentRound)
                .toList();

        if (policy.mode() == ConsensusMode.OFF) {
            return new ConsensusResult(true, roundVotes, List.of(), "consensus disabled");
        }

        // UNANIMOUS — the only other mode today.
        if (expectedAgentIds.isEmpty()) {
            // Nothing to block, but nothing was actually agreed to either — a caller-configuration
            // issue (an empty expected-agent set), not a legitimate unanimous consensus.
            return new ConsensusResult(false, roundVotes, List.of(), "no expected agents configured");
        }

        Map<String, ConsensusVote> latestByAgent = roundVotes.stream()
                .collect(Collectors.toMap(ConsensusVote::agentId, v -> v, (first, last) -> last));

        List<String> sortedExpected = expectedAgentIds.stream().sorted().toList();
        List<String> blocking = new ArrayList<>();
        for (String agentId : sortedExpected) {
            ConsensusVote v = latestByAgent.get(agentId);
            boolean missing = v == null;
            boolean blankRationale = !missing && policy.requireRationale() && v.rationale().isBlank();
            if (missing || v.decision() != VoteDecision.APPROVE || blankRationale) {
                blocking.add(agentId);
            }
        }

        if (blocking.isEmpty()) {
            return new ConsensusResult(true, roundVotes, List.of(),
                    "unanimous approval from " + sortedExpected.size() + " agent(s)");
        }
        String reason = blocking.size() == sortedExpected.size()
                ? "no agent approved"
                : blocking.size() + " of " + sortedExpected.size() + " agent(s) blocking: "
                        + String.join(", ", blocking);
        return new ConsensusResult(false, roundVotes, List.copyOf(blocking), reason);
    }
}
