package com.jarvis.discussion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.discussion.DiscussionRunner.Chair;
import com.jarvis.discussion.DiscussionRunner.Round;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscussionRunnerTest {

    /** A chair that asks a fixed number of questions, then converges, and synthesizes a summary. */
    private static Chair chairAsking(int questions) {
        return new Chair() {
            @Override
            public String next(String topic, List<Round> soFar) {
                return soFar.size() < questions
                        ? "Q" + (soFar.size() + 1) + " about " + topic
                        : DiscussionRunner.CONVERGED_MARKER;
            }

            @Override
            public String synthesize(String topic, List<Round> rounds, boolean converged) {
                return "outcome after " + rounds.size() + " rounds, converged=" + converged;
            }
        };
    }

    @Test
    void runsUntilTheChairConvergesAndSynthesizes() {
        DiscussionRunner.Discussion d = new DiscussionRunner()
                .run("the go-kart budget", chairAsking(2), q -> "advisor says: " + q);
        assertEquals(2, d.rounds().size());
        assertTrue(d.converged());
        assertEquals("advisor says: Q1 about the go-kart budget", d.rounds().get(0).answer());
        assertTrue(d.outcome().contains("converged=true"));
    }

    @Test
    void neverExceedsTheHardRoundBudget() {
        // A chair that never converges is capped at MAX_ROUNDS.
        Chair neverDone = new Chair() {
            public String next(String topic, List<Round> soFar) {
                return "keep going";
            }

            public String synthesize(String topic, List<Round> rounds, boolean converged) {
                return "done";
            }
        };
        DiscussionRunner.Discussion d = new DiscussionRunner().run("x", neverDone, q -> "ok");
        assertEquals(DiscussionRunner.MAX_ROUNDS, d.rounds().size());
        assertFalse(d.converged());
    }

    @Test
    void anAdvisorFailureIsAnExplicitErrorRoundNotSilentSuccess() {
        DiscussionRunner.Discussion d = new DiscussionRunner().run("x", chairAsking(3), q -> {
            throw new RuntimeException("core offline");
        });
        assertEquals(1, d.rounds().size());
        assertTrue(d.rounds().get(0).failed());
        assertTrue(d.rounds().get(0).error().contains("core offline"));
        assertFalse(d.converged());   // a failed round never counts as convergence
    }

    @Test
    void theChairSeesTheTranscriptSoFar() {
        Chair counting = new Chair() {
            public String next(String topic, List<Round> soFar) {
                return soFar.size() >= 2 ? DiscussionRunner.CONVERGED_MARKER
                        : "round-" + soFar.size();
            }

            public String synthesize(String topic, List<Round> rounds, boolean converged) {
                return "n=" + rounds.size();
            }
        };
        DiscussionRunner.Discussion d = new DiscussionRunner().run("t", counting, q -> "a");
        assertEquals("n=2", d.outcome());
    }
}
