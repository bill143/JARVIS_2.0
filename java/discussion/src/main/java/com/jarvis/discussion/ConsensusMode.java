package com.jarvis.discussion;

/** How (or whether) a discussion round requires agreement among its participants before proceeding. */
public enum ConsensusMode {

    /** No consensus check — the discussion behaves exactly as it always has. */
    OFF,

    /** Every expected agent must vote {@link VoteDecision#APPROVE} in the current round. */
    UNANIMOUS
}
