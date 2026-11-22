package com.jarvis.discussion;

/** One agent's vote on whether a discussion round's outcome is settled. */
public enum VoteDecision {

    /** The agent agrees the round's answer/outcome is acceptable. */
    APPROVE,

    /** The agent explicitly disagrees — always blocks unanimity. */
    REJECT,

    /** The agent declines to take a position — blocks unanimity (silence is not agreement). */
    ABSTAIN
}
