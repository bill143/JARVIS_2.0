package com.jarvis.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GreetingOrchestratorTest {

    private final GreetingOrchestrator orch = new GreetingOrchestrator(new IdentityConfidencePolicy(0.75));

    private static PresenceSignal known() {
        return new PresenceSignal(true, "Bill", 0.9, true, false);
    }

    @Test
    void highConfidenceOptInGivesPersonalizedGreeting() {
        GreetingOutcome o = orch.onPresence(known(), ContextGuards.none());
        assertTrue(o.greeted());
        assertEquals(GreetingType.PERSONALIZED, o.type());
        assertTrue(o.text().contains("Bill"));
    }

    @Test
    void lowConfidenceGivesNeutralText() {
        GreetingOutcome o = orch.onPresence(
                new PresenceSignal(true, "Bill", 0.4, true, false), ContextGuards.none());
        assertTrue(o.greeted());
        assertEquals(GreetingType.NEUTRAL, o.type());
        assertFalse(o.text().contains("Bill"));
    }

    @Test
    void quietHoursSuppress() {
        GreetingOutcome o = orch.onPresence(known(), new ContextGuards(true, false, false));
        assertFalse(o.greeted());
        assertEquals(GreetingType.NONE, o.type());
        assertTrue(o.reason().contains("quiet-hours"));
    }

    @Test
    void meetingSuppress() {
        GreetingOutcome o = orch.onPresence(known(), new ContextGuards(false, true, false));
        assertFalse(o.greeted());
        assertTrue(o.reason().contains("in-meeting"));
    }

    @Test
    void dndSuppress() {
        GreetingOutcome o = orch.onPresence(known(), new ContextGuards(false, false, true));
        assertFalse(o.greeted());
        assertTrue(o.reason().contains("do-not-disturb"));
    }

    @Test
    void noPersonDoesNotGreet() {
        GreetingOutcome o = orch.onPresence(
                new PresenceSignal(false, null, 0, false, false), ContextGuards.none());
        assertFalse(o.greeted());
        assertEquals(GreetingType.NONE, o.type());
    }
}
