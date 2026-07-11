package com.jarvis.audit;

/** What kind of activity an audit entry records. */
public enum AuditCategory {

    /** A tool was invoked through the agent loop. */
    TOOL_INVOCATION,

    /** A destructive/irreversible action ran (delete, power off, send). */
    DESTRUCTIVE_ACTION,

    /** An outbound call to an external service (LLM, Google, web). */
    EXTERNAL_API,

    /** An internal/system event (startup, permission decision, config change). */
    SYSTEM
}
