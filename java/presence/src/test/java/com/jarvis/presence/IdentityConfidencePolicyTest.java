package com.jarvis.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class IdentityConfidencePolicyTest {

    private final IdentityConfidencePolicy policy = new IdentityConfidencePolicy(0.75);

    @Test
    void highConfidenceWithConsentIsPersonalized() {
        var d = policy.decide(new PresenceSignal(true, "Bill", 0.9, true, false));
        assertEquals(GreetingType.PERSONALIZED, d.type());
        assertEquals("Bill", d.name());
    }

    @Test
    void lowConfidenceIsNeutral() {
        var d = policy.decide(new PresenceSignal(true, "Bill", 0.5, true, false));
        assertEquals(GreetingType.NEUTRAL, d.type());
        assertNull(d.name());   // no identity used below threshold
    }

    @Test
    void noConsentStaysNeutralEvenAtHighConfidence() {
        var d = policy.decide(new PresenceSignal(true, "Bill", 0.99, false, false));
        assertEquals(GreetingType.NEUTRAL, d.type());
        assertNull(d.name());   // opt-in required
    }

    @Test
    void privateModeSuppressesPersonalization() {
        var d = policy.decide(new PresenceSignal(true, "Bill", 0.99, true, true));
        assertEquals(GreetingType.NEUTRAL, d.type());
        assertNull(d.name());
    }

    @Test
    void noPersonIsNone() {
        assertEquals(GreetingType.NONE,
                policy.decide(new PresenceSignal(false, null, 0, false, false)).type());
    }

    @Test
    void identifiedButUnknownNameIsNeutral() {
        var d = policy.decide(new PresenceSignal(true, "  ", 0.9, true, false));
        assertEquals(GreetingType.NEUTRAL, d.type());
    }
}
