package com.jarvis.discussion;

import java.util.Objects;

/**
 * One agent's vote in one round of a consensus-gated discussion.
 *
 * @param agentId who cast the vote (e.g. {@code "chair"}, {@code "openhuman"}, {@code "model:X"})
 * @param decision what they voted
 * @param rationale why (may be blank; {@link ConsensusPolicy#requireRationale()} governs whether a
 *     blank rationale is acceptable — that policy check happens in {@link ConsensusGate}, not here)
 * @param round which round this vote belongs to (1-indexed, matching {@code DiscussionRunner.Round})
 */
public record ConsensusVote(String agentId, VoteDecision decision, String rationale, int round) {

    public ConsensusVote {
        Objects.requireNonNull(agentId, "agentId");
        if (agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        Objects.requireNonNull(decision, "decision");
        rationale = rationale == null ? "" : rationale;
        if (round < 1) {
            throw new IllegalArgumentException("round must be >= 1, got " + round);
        }
    }
}
