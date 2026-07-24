package com.jarvis.discussion;

import java.util.Objects;

/**
 * The rules a discussion's consensus check runs under.
 *
 * @param mode {@link ConsensusMode#OFF} disables the check entirely
 * @param maxRounds the round ceiling to attempt consensus within ({@code >= 1})
 * @param requireRationale when {@code true}, a vote with a blank rationale is treated by
 *     {@link ConsensusGate} as though it were {@link VoteDecision#ABSTAIN} — silence without
 *     reasoning cannot carry an approval
 * @param timeoutMs how long a caller should wait for a single agent's vote before treating it as
 *     missing ({@code >= 0}; {@code 0} means no timeout). {@link ConsensusGate} itself is pure and
 *     has no clock — enforcing this is the caller's (e.g. {@code DiscussionRunner}'s) responsibility
 */
public record ConsensusPolicy(ConsensusMode mode, int maxRounds, boolean requireRationale,
        long timeoutMs) {

    public ConsensusPolicy {
        Objects.requireNonNull(mode, "mode");
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds must be >= 1, got " + maxRounds);
        }
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("timeoutMs must be >= 0, got " + timeoutMs);
        }
    }

    /** The inert default: consensus disabled, everything else at its documented default. */
    public static ConsensusPolicy off() {
        return new ConsensusPolicy(ConsensusMode.OFF, 3, true, 5000L);
    }
}
