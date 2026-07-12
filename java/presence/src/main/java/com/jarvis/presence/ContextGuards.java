package com.jarvis.presence;

/**
 * Context conditions that must block a proactive greeting (RFC / Phase-1 mandatory guards). If any
 * is set, JARVIS stays silent — it never greets during quiet hours, a meeting, or Do-Not-Disturb.
 *
 * @param quietHours the user's configured quiet window is active
 * @param inMeeting a meeting is in progress
 * @param doNotDisturb DnD is enabled
 */
public record ContextGuards(boolean quietHours, boolean inMeeting, boolean doNotDisturb) {

    /** No guards active — greeting is allowed (subject to identity policy). */
    public static ContextGuards none() {
        return new ContextGuards(false, false, false);
    }

    /** Whether any guard is active and a proactive greeting must be suppressed. */
    public boolean suppresses() {
        return quietHours || inMeeting || doNotDisturb;
    }

    /** A short, auditable reason string for the active guard(s). */
    public String reason() {
        StringBuilder sb = new StringBuilder();
        if (quietHours) {
            sb.append("quiet-hours ");
        }
        if (inMeeting) {
            sb.append("in-meeting ");
        }
        if (doNotDisturb) {
            sb.append("do-not-disturb ");
        }
        return sb.toString().strip();
    }
}
