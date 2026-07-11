package com.jarvis.autonomous;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AutonomousRunnerTest {

    @Test
    void stopsWhenTheAgentSignalsDone() {
        AtomicInteger i = new AtomicInteger();
        AutonomousRunner.AutonomousRun run = new AutonomousRunner().run("tidy up", (goal, progress) ->
                i.incrementAndGet() == 3 ? "finished " + AutonomousRunner.DONE_MARKER : "working");
        assertTrue(run.completed());
        assertEquals(3, run.steps().size());
    }

    @Test
    void neverExceedsTheHardStepBudget() {
        AtomicInteger i = new AtomicInteger();
        AutonomousRunner.AutonomousRun run = new AutonomousRunner(100).run("infinite", (g, p) -> {
            i.incrementAndGet();
            return "still going";   // never signals done
        });
        assertEquals(AutonomousRunner.MAX_STEPS, i.get());
        assertFalse(run.completed());
    }

    @Test
    void feedsProgressBackToTheAgent() {
        AutonomousRunner.AutonomousRun run = new AutonomousRunner(2).run("count", (goal, progress) ->
                progress.equals("(nothing done yet)") ? "step one" : "saw: " + progress + " " + AutonomousRunner.DONE_MARKER);
        assertTrue(run.completed());
        assertTrue(run.steps().get(1).contains("saw: step one"));
    }

    @Test
    void aFailingStepIsCapturedNotThrown() {
        AutonomousRunner.AutonomousRun run = new AutonomousRunner(1).run("x", (g, p) -> {
            throw new IllegalStateException("boom");
        });
        assertTrue(run.steps().get(0).contains("boom"));
    }
}
