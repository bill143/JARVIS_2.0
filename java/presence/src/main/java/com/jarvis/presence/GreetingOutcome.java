package com.jarvis.presence;

/**
 * The result of evaluating a presence event: whether a greeting was produced, of what kind, its
 * text, and an auditable reason. A suppressed or no-op result still carries a reason so the
 * proactive decision is always explainable in the audit log.
 *
 * @param greeted whether a greeting was actually produced
 * @param type the greeting kind (NONE when not greeted)
 * @param text the greeting text ({@code ""} when not greeted)
 * @param reason a short, auditable explanation of the decision
 */
public record GreetingOutcome(boolean greeted, GreetingType type, String text, String reason) {

    public GreetingOutcome {
        text = text == null ? "" : text;
        reason = reason == null ? "" : reason;
    }

    /** A suppressed / not-greeted outcome carrying {@code reason}. */
    public static GreetingOutcome silent(String reason) {
        return new GreetingOutcome(false, GreetingType.NONE, "", reason);
    }
}
