package com.jarvis.discussion;

import java.util.List;

/**
 * The outcome of evaluating one round's votes against a {@link ConsensusPolicy}.
 *
 * @param achieved whether consensus was reached in that round
 * @param votes every vote considered (only the current round's — see {@link ConsensusGate})
 * @param blockingAgents agent ids that prevented consensus: anyone who voted non-{@code APPROVE},
 *     plus any expected agent who never voted at all — empty when {@code achieved}
 * @param reason a short human-readable explanation, never {@code null}
 */
public record ConsensusResult(boolean achieved, List<ConsensusVote> votes,
        List<String> blockingAgents, String reason) {

    public ConsensusResult {
        votes = votes == null ? List.of() : List.copyOf(votes);
        blockingAgents = blockingAgents == null ? List.of() : List.copyOf(blockingAgents);
        reason = reason == null ? "" : reason;
    }
}
