package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Guards the re-architected multi-agent engine (#2). */
class AgentTeamServiceTest {

    private static AgentTeamService team(AgentTeamService.Turn turn) {
        return new AgentTeamService(turn, null, null);
    }

    @Test
    void composeSelectsOnlyTheRolesTheTaskNeeds() {
        AgentTeamService s = team((role, prompt) -> "");
        assertEquals(List.of("executor"), s.compose("what is 2+2?"));
        assertEquals(List.of("planner", "executor"), s.compose("give me a plan, step by step"));
        assertTrue(s.compose("verify this calculation carefully").contains("critic"));
        assertFalse(s.compose("say hi").contains("critic"));
    }

    @Test
    void specializedRolesGetDistinctPromptsPerRole() {
        java.util.Map<String, String> systems = new java.util.concurrent.ConcurrentHashMap<>();
        AgentTeamService s = team((role, prompt) -> {
            systems.put(role.role(), role.system());
            return "planner".equals(role.role()) ? "step one\nstep two"
                    : "critic".equals(role.role()) ? "PASS" : "done";
        });
        s.run("plan and verify this carefully", new AgentTeamService.Budget(0, 0, 0));
        assertTrue(systems.get("planner").contains("PLANNER"));
        assertTrue(systems.get("executor").contains("EXECUTOR"));
        assertTrue(systems.get("critic").contains("CRITIC"));
    }

    @Test
    void independentSubtasksRunConcurrentlyOnVirtualThreads() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        AgentTeamService s = team((role, prompt) -> {
            if ("planner".equals(role.role())) {
                return "task A\ntask B\ntask C\ntask D";
            }
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(40);   // hold the slot so overlap is observable
            } finally {
                inFlight.decrementAndGet();
            }
            return "ok";
        });
        AgentTeamService.TeamResult r = s.run("plan and execute these steps",
                new AgentTeamService.Budget(0, 0, 0));
        assertTrue(maxInFlight.get() >= 2, "expected concurrent executors, saw " + maxInFlight.get());
        assertTrue(r.answer().contains("task A") && r.answer().contains("task D"));
    }

    @Test
    void criticFailureRoutesFeedbackAndRetriesUntilPass() {
        AtomicInteger execCalls = new AtomicInteger();
        AtomicInteger criticCalls = new AtomicInteger();
        AgentTeamService s = team((role, prompt) -> {
            switch (role.role()) {
                case "executor" -> {
                    execCalls.incrementAndGet();
                    return "attempt";
                }
                case "critic" -> {
                    // Fail twice, then pass on the third review.
                    return criticCalls.incrementAndGet() < 3 ? "FAIL: be more specific" : "PASS";
                }
                default -> {
                    return "";
                }
            }
        });
        AgentTeamService.TeamResult r = s.run("verify this answer carefully",
                new AgentTeamService.Budget(0, 0, 0));
        assertTrue(r.approvedByCritic());
        assertEquals(2, r.retries());
        assertEquals(3, execCalls.get());   // 1 initial + 2 corrective passes
    }

    @Test
    void selfCorrectionStopsAtTheRetryCap() {
        AgentTeamService s = team((role, prompt) ->
                "critic".equals(role.role()) ? "FAIL: still wrong" : "work");
        AgentTeamService.TeamResult r = s.run("review and correct this rigorously",
                new AgentTeamService.Budget(0, 0, 0));
        assertFalse(r.approvedByCritic());
        assertEquals(AgentTeamService.MAX_RETRIES, r.retries());
        assertTrue(r.scratchpad().containsKey("degraded"));
    }

    @Test
    void budgetTrackingDegradesNearLimitInsteadOfRetrying() {
        // A tiny token budget: the run should stop retrying (degrade) rather than loop to the cap.
        AgentTeamService s = team((role, prompt) ->
                "critic".equals(role.role()) ? "FAIL: keep going" : "a fairly wordy executor answer here");
        AgentTeamService.TeamResult r = s.run("carefully verify this at length",
                new AgentTeamService.Budget(20, 0, 0));   // ~20 token ceiling
        assertTrue(r.usage().tokens() > 0);
        assertTrue(r.usage().nearLimit() || r.usage().exceeded());
        assertTrue(r.retries() < AgentTeamService.MAX_RETRIES, "should degrade before the cap");
        assertTrue(r.scratchpad().containsKey("degraded"));
    }

    @Test
    void groundingNotesEnterTheSharedScratchpad() {
        AgentTeamService s = new AgentTeamService((role, prompt) -> "done", null,
                task -> List.of("Note: the vault says use metric units"));
        AgentTeamService.TeamResult r = s.run("do the thing", new AgentTeamService.Budget(0, 0, 0));
        assertTrue(r.scratchpad().getOrDefault("grounding", "").contains("metric units"));
    }

    @Test
    void humanInTheLoopRedirectReachesTheRunAndUnknownRunsAreRejected() throws Exception {
        AgentTeamService s = team((role, prompt) -> {
            try {
                Thread.sleep(30);   // give the redirect time to land at a checkpoint
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "critic".equals(role.role()) ? "PASS"
                    : "planner".equals(role.role()) ? "one\ntwo" : "work";
        });
        String id = s.start("plan and verify this carefully", new AgentTeamService.Budget(0, 0, 0));
        assertTrue(s.redirect(id, "focus on cost"));
        assertFalse(s.redirect("nope", "x"));
        assertFalse(s.redirect(id, ""));
        // Wait for completion.
        for (int i = 0; i < 200; i++) {
            AgentTeamService.TeamResult r = s.status(id);
            if (r != null && (r.status().equals("done") || r.status().startsWith("paused"))) {
                break;
            }
            Thread.sleep(20);
        }
        AgentTeamService.TeamResult done = s.status(id);
        assertTrue(done.scratchpad().getOrDefault("human", "").contains("focus on cost"));
    }
}
