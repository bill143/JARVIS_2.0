package com.jarvis.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MultiAgentManagerTest {

    @Test
    void runsPlannerExecutorCriticExecutorAndReturnsTheFinalAnswer() {
        MultiAgentManager mgr = new MultiAgentManager();
        MultiAgentManager.Conversation c = mgr.run("build a go-kart",
                (role, prompt) -> role + " handled it");

        assertEquals(4, c.messages().size());
        assertEquals(Role.PLANNER, c.messages().get(0).role());
        assertEquals(Role.EXECUTOR, c.messages().get(1).role());
        assertEquals(Role.CRITIC, c.messages().get(2).role());
        assertEquals(Role.EXECUTOR, c.messages().get(3).role());
        assertEquals("EXECUTOR handled it", c.result());   // final executor turn
    }

    @Test
    void neverExceedsTheHardTurnBudget() {
        AtomicInteger turns = new AtomicInteger();
        // Even asking for 100 turns, the manager caps at MAX_TURNS.
        new MultiAgentManager(100).run("x", (role, prompt) -> {
            turns.incrementAndGet();
            return "ok";
        });
        assertTrue(turns.get() <= MultiAgentManager.MAX_TURNS);
    }

    @Test
    void aTightBudgetStopsEarly() {
        AtomicInteger turns = new AtomicInteger();
        MultiAgentManager.Conversation c = new MultiAgentManager(2).run("x", (role, prompt) -> {
            turns.incrementAndGet();
            return "ok";
        });
        assertEquals(2, turns.get());        // planner + executor only
        assertEquals(2, c.messages().size());
    }

    @Test
    void aFailingTurnIsCapturedNotThrown() {
        MultiAgentManager.Conversation c = new MultiAgentManager().run("x", (role, prompt) -> {
            throw new IllegalStateException("boom");
        });
        assertTrue(c.messages().get(0).content().contains("boom"));
    }
}
