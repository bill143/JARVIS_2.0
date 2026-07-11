package com.jarvis.security;

/** What the policy says to do about a proposed action. */
public enum PermissionDecision {

    /** Run it without asking. */
    ALLOW,

    /** Ask the user first. */
    PROMPT
}
