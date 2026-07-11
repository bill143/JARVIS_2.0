package com.jarvis.agents;

/** A role in a multi-agent conversation (the AutoGen-style pattern). */
public enum Role {

    /** Breaks the goal into a plan. */
    PLANNER,

    /** Carries out the plan (with tools, in production). */
    EXECUTOR,

    /** Reviews the result and points out gaps. */
    CRITIC
}
