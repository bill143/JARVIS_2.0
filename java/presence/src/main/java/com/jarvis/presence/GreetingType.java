package com.jarvis.presence;

/** How a proactive greeting addresses the person. */
public enum GreetingType {

    /** Address the person by name — only when identity is consented AND high-confidence. */
    PERSONALIZED,

    /** A neutral greeting with no identity used (low confidence, no consent, or private mode). */
    NEUTRAL,

    /** No greeting at all (no person present, or suppressed by context). */
    NONE
}
