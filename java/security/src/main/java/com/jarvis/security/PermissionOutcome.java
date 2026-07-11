package com.jarvis.security;

/** The result of asking the user to confirm an action. */
public enum PermissionOutcome {

    /** The user approved it. */
    ALLOWED,

    /** The user rejected it. */
    DENIED,

    /** No answer arrived in time — treated as a denial (fail-closed). */
    TIMED_OUT
}
