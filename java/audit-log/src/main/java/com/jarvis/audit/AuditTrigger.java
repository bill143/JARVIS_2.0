package com.jarvis.audit;

/** What caused an audited action to run. */
public enum AuditTrigger {

    /** Directly requested by the user (a chat/voice command). */
    USER,

    /** Run by the agent on its own — an autonomous plan/workflow step. */
    AUTONOMOUS,

    /** Emitted by the system itself, not on anyone's behalf. */
    SYSTEM
}
