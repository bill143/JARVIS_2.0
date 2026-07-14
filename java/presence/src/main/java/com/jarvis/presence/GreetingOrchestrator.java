package com.jarvis.presence;

import java.util.Objects;

/**
 * Turns a camera-presence event into a proactive greeting decision. Order of checks (all mandatory):
 *
 * <ol>
 *   <li><b>Context guards first</b> — quiet hours / meeting / DnD suppress any greeting.</li>
 *   <li><b>Identity policy</b> — {@link IdentityConfidencePolicy} decides personalized vs neutral
 *       (opt-in + confidence required for personalization).</li>
 * </ol>
 *
 * Pure logic — no I/O, no clock. The caller supplies the {@link ContextGuards} (evaluated from the
 * user's settings/calendar) and audits the returned {@link GreetingOutcome}.
 */
public final class GreetingOrchestrator {

    private final IdentityConfidencePolicy policy;

    public GreetingOrchestrator() {
        this(new IdentityConfidencePolicy());
    }

    public GreetingOrchestrator(IdentityConfidencePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** Decides whether/how to greet for {@code signal} under {@code guards}. */
    public GreetingOutcome onPresence(PresenceSignal signal, ContextGuards guards) {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(guards, "guards");
        if (guards.suppresses()) {
            return GreetingOutcome.silent("suppressed by context: " + guards.reason());
        }
        IdentityConfidencePolicy.Decision d = policy.decide(signal);
        return switch (d.type()) {
            case NONE -> GreetingOutcome.silent(d.reason());
            case PERSONALIZED -> new GreetingOutcome(true, GreetingType.PERSONALIZED,
                    "Good to see you, " + d.name() + ". How can I help?", d.reason());
            case NEUTRAL -> new GreetingOutcome(true, GreetingType.NEUTRAL,
                    "Hello — how can I help?", d.reason());
        };
    }
}
