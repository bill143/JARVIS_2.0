package com.jarvis.presence;

import java.util.Objects;

/**
 * Decides <em>whether and how</em> a detected person may be greeted by identity. The safety rule is
 * strict and opt-in: a person is addressed by name <b>only</b> when all three hold —
 *
 * <ol>
 *   <li>the user has <b>opted in</b> to identity personalization ({@link PresenceSignal#consentGiven}),</li>
 *   <li>identity confidence is at or above the configured threshold, and</li>
 *   <li>private mode is off.</li>
 * </ol>
 *
 * Otherwise the greeting is {@link GreetingType#NEUTRAL} (no identity used), and if no person is
 * present it is {@link GreetingType#NONE}. Pure and local — no I/O, no biometrics, so identity never
 * leaves the device through this layer.
 */
public final class IdentityConfidencePolicy {

    /** Default confidence threshold for personalization. */
    public static final double DEFAULT_THRESHOLD = 0.75;

    private final double threshold;

    public IdentityConfidencePolicy() {
        this(DEFAULT_THRESHOLD);
    }

    public IdentityConfidencePolicy(double threshold) {
        this.threshold = Math.min(Math.max(0, threshold), 1);
    }

    public double threshold() {
        return threshold;
    }

    /** The personalization decision for {@code signal}. */
    public Decision decide(PresenceSignal signal) {
        Objects.requireNonNull(signal, "signal");
        if (!signal.personPresent()) {
            return new Decision(GreetingType.NONE, null, "no person present");
        }
        boolean named = signal.identifiedName() != null && !signal.identifiedName().isBlank();
        if (signal.privateMode()) {
            return new Decision(GreetingType.NEUTRAL, null, "private mode — identity suppressed");
        }
        if (!signal.consentGiven()) {
            return new Decision(GreetingType.NEUTRAL, null, "no opt-in for identity personalization");
        }
        if (!named) {
            return new Decision(GreetingType.NEUTRAL, null, "person not identified");
        }
        if (signal.confidence() < threshold) {
            return new Decision(GreetingType.NEUTRAL, null,
                    "confidence " + signal.confidence() + " below threshold " + threshold);
        }
        return new Decision(GreetingType.PERSONALIZED, signal.identifiedName(),
                "opt-in + confidence " + signal.confidence() + " >= " + threshold);
    }

    /**
     * The outcome of a personalization decision.
     *
     * @param type how to greet
     * @param name the name to use — non-null ONLY for {@link GreetingType#PERSONALIZED}
     * @param reason a short, auditable explanation
     */
    public record Decision(GreetingType type, String name, String reason) {
    }
}
