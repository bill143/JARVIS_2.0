package com.jarvis.presence;

/**
 * A single camera-presence observation fed to the greeting logic. Deliberately a plain value with
 * no image data — identity resolution happens upstream (locally, on-device via the vision model);
 * this carries only the <em>outcome</em>, so the policy layer never touches raw biometrics.
 *
 * @param personPresent whether a person was detected at all
 * @param identifiedName the resolved name, or {@code null} if unknown / not identified
 * @param confidence identity confidence in [0,1] (0 when unknown)
 * @param consentGiven whether the user has opted in to identity-based personalization (opt-in only)
 * @param privateMode whether private mode is on (identity personalization is suppressed if so)
 */
public record PresenceSignal(boolean personPresent, String identifiedName, double confidence,
        boolean consentGiven, boolean privateMode) {

    public PresenceSignal {
        if (confidence < 0) {
            confidence = 0;
        }
        if (confidence > 1) {
            confidence = 1;
        }
    }

    /** A bare "someone is here" signal with no identity (unknown person, no consent needed). */
    public static PresenceSignal anonymous(boolean privateMode) {
        return new PresenceSignal(true, null, 0, false, privateMode);
    }
}
