package com.jarvis.audit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.tools.RiskTier;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuditQueryTest {

    private static AuditEntry entry(Instant at, String action, AuditTrigger trigger,
            AuditOutcome outcome) {
        return new AuditEntry(0, at,
                new AuditEvent(AuditCategory.TOOL_INVOCATION, action, trigger,
                        RiskTier.READ_ONLY, outcome, ""));
    }

    private static final Instant T = Instant.parse("2026-07-11T12:00:00Z");

    @Test
    void emptyQueryMatchesEverything() {
        assertTrue(AuditQuery.all().matches(entry(T, "clock", AuditTrigger.USER, AuditOutcome.SUCCESS)));
    }

    @Test
    void actionFilterIsCaseInsensitiveSubstring() {
        AuditEntry e = entry(T, "email_trash", AuditTrigger.USER, AuditOutcome.SUCCESS);
        assertTrue(AuditQuery.all().action("EMAIL").matches(e));
        assertTrue(AuditQuery.all().action("trash").matches(e));
        assertFalse(AuditQuery.all().action("calendar").matches(e));
    }

    @Test
    void triggerAndOutcomeFiltersAreExact() {
        AuditEntry e = entry(T, "power", AuditTrigger.AUTONOMOUS, AuditOutcome.FAILURE);
        assertTrue(AuditQuery.all().trigger(AuditTrigger.AUTONOMOUS).matches(e));
        assertFalse(AuditQuery.all().trigger(AuditTrigger.USER).matches(e));
        assertTrue(AuditQuery.all().outcome(AuditOutcome.FAILURE).matches(e));
        assertFalse(AuditQuery.all().outcome(AuditOutcome.SUCCESS).matches(e));
    }

    @Test
    void timeBoundsAreInclusive() {
        AuditEntry e = entry(T, "clock", AuditTrigger.USER, AuditOutcome.SUCCESS);
        assertTrue(AuditQuery.all().from(T).to(T).matches(e));                 // exactly on both bounds
        assertFalse(AuditQuery.all().from(T.plusSeconds(1)).matches(e));       // before window
        assertFalse(AuditQuery.all().to(T.minusSeconds(1)).matches(e));        // after window
    }
}
