package com.jarvis.workflows;

/** How a workflow starts. */
public enum TriggerType {

    /** Run only when the user clicks Run. */
    MANUAL,

    /** Run automatically on a fixed interval (a JDK scheduler). */
    SCHEDULE,

    /** Run when its webhook endpoint is POSTed. */
    WEBHOOK
}
