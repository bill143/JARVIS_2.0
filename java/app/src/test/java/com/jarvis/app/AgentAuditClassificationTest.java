package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.agents.Role;
import com.jarvis.api.ChatRequest;
import com.jarvis.api.ChatResponse;
import com.jarvis.api.JarvisApi;
import com.jarvis.api.PlanRequest;
import com.jarvis.api.PlanResponse;
import com.jarvis.audit.AuditEntry;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.RecordStoreAuditLog;
import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.tools.RiskTier;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards issue #5: agent sub-role turns and autonomous steps must be classified into the strict
 * risk taxonomy ({@code READ_ONLY} / {@code MUTATING} / {@code DESTRUCTIVE}) rather than recorded
 * as {@code UNKNOWN}.
 */
class AgentAuditClassificationTest {

    /** A fake governed API: for autonomous runs it signals completion so the loop ends after one step. */
    private static JarvisApi fakeApi(boolean signalDone) {
        return new JarvisApi() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                String reply = "did the work" + (signalDone ? " GOAL_DONE" : "");
                return new ChatResponse("s", true, reply, 0);
            }

            @Override
            public PlanResponse plan(PlanRequest request) {
                throw new UnsupportedOperationException("not used");
            }
        };
    }

    @Test
    void roleClassificationMapsPlannerAndCriticReadOnlyExecutorMutating() {
        assertEquals(RiskTier.READ_ONLY, MultiAgentService.riskFor(Role.PLANNER));
        assertEquals(RiskTier.READ_ONLY, MultiAgentService.riskFor(Role.CRITIC));
        assertEquals(RiskTier.MUTATING, MultiAgentService.riskFor(Role.EXECUTOR));
    }

    @Test
    void multiAgentTurnsAreNeverRecordedUnknown() {
        AuditLog audit = new RecordStoreAuditLog(new InMemoryRecordStore());
        new MultiAgentService(fakeApi(false), audit).run("plan a picnic");

        List<AuditEntry> agentTurns = audit.recent(50).stream()
                .filter(e -> e.event().action().startsWith("agent:")).toList();
        assertFalse(agentTurns.isEmpty(), "expected agent:<role> audit entries");
        for (AuditEntry e : agentTurns) {
            assertTrue(e.event().riskTier() != RiskTier.UNKNOWN,
                    e.event().action() + " must be classified, was UNKNOWN");
        }
        // The PLANNER/CRITIC turns are read-only; the EXECUTOR turn is mutating.
        assertTrue(agentTurns.stream()
                .anyMatch(e -> e.event().action().equals("agent:EXECUTOR")
                        && e.event().riskTier() == RiskTier.MUTATING));
        assertTrue(agentTurns.stream()
                .anyMatch(e -> e.event().action().equals("agent:PLANNER")
                        && e.event().riskTier() == RiskTier.READ_ONLY));
    }

    @Test
    void autonomousStepsAreClassifiedMutating() {
        AuditLog audit = new RecordStoreAuditLog(new InMemoryRecordStore());
        new AutonomousService(fakeApi(true), audit).run("tidy the inbox");

        List<AuditEntry> steps = audit.recent(50).stream()
                .filter(e -> e.event().action().equals("autonomous-step")).toList();
        assertFalse(steps.isEmpty(), "expected autonomous-step audit entries");
        for (AuditEntry e : steps) {
            assertEquals(RiskTier.MUTATING, e.event().riskTier());
        }
    }
}
