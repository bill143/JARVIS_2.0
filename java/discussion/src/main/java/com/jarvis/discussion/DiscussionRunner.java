package com.jarvis.discussion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A bounded, project-scoped discussion loop: a {@link Chair} (JARVIS) puts questions to an
 * {@link Advisor} (e.g. OpenHuman) for a few rounds, then synthesizes an outcome. The chair drives
 * and decides — the advisor only contributes — so the hierarchy is "JARVIS chairs, the advisor
 * advises". The loop is hard-bounded ({@link #MAX_ROUNDS}) so two models cannot talk forever.
 *
 * <p>An advisor call that fails is recorded as an explicit error round and ends the discussion as
 * <em>not converged</em> — never silently treated as "nothing to add".
 */
public final class DiscussionRunner {

    /** Hard ceiling on rounds — the run-level budget; the loop cannot run away. */
    public static final int MAX_ROUNDS = 6;

    /** Marker the chair may return from {@link Chair#next} to end the discussion early. */
    public static final String CONVERGED_MARKER = "DISCUSSION_DONE";

    /** One question→answer exchange. {@code error} is set only when the advisor call failed. */
    public record Round(int index, String question, String answer, String error) {
        public boolean failed() {
            return error != null;
        }
    }

    /** The result of a discussion: the full transcript plus the chair's synthesized outcome. */
    public record Discussion(String topic, List<Round> rounds, String outcome, boolean converged) {
        public Discussion {
            rounds = rounds == null ? List.of() : List.copyOf(rounds);
        }
    }

    /** The advisor being consulted (OpenHuman in production; a fake in tests). */
    @FunctionalInterface
    public interface Advisor {
        String respond(String question) throws Exception;
    }

    /** The chair (JARVIS): decides the next question and writes the final outcome. */
    public interface Chair {
        /**
         * The next question to put to the advisor given the transcript so far. Returning a string
         * that starts with {@link #CONVERGED_MARKER} ends the discussion (converged).
         */
        String next(String topic, List<Round> soFar) throws Exception;

        /** The final decision/plan/summary synthesized from the whole discussion. */
        String synthesize(String topic, List<Round> rounds, boolean converged) throws Exception;
    }

    private final int maxRounds;

    public DiscussionRunner() {
        this(MAX_ROUNDS);
    }

    public DiscussionRunner(int maxRounds) {
        this.maxRounds = Math.min(Math.max(1, maxRounds), MAX_ROUNDS);
    }

    /** Runs the bounded discussion between {@code chair} and {@code advisor} on {@code topic}. */
    public Discussion run(String topic, Chair chair, Advisor advisor) {
        Objects.requireNonNull(chair, "chair");
        Objects.requireNonNull(advisor, "advisor");
        List<Round> rounds = new ArrayList<>();
        boolean converged = false;
        for (int i = 1; i <= maxRounds; i++) {
            String question;
            try {
                question = chair.next(topic, List.copyOf(rounds));
            } catch (Exception e) {
                rounds.add(new Round(i, null, null, "chair error: " + e.getMessage()));
                break;
            }
            if (question == null || question.strip().startsWith(CONVERGED_MARKER)) {
                converged = true;
                break;
            }
            try {
                String answer = advisor.respond(question);
                rounds.add(new Round(i, question, answer, null));
            } catch (Exception e) {
                // Surface the failure as an explicit error round; do not treat as "no findings".
                rounds.add(new Round(i, question, null, "advisor error: " + e.getMessage()));
                break;
            }
        }
        boolean anyFailure = rounds.stream().anyMatch(Round::failed);
        String outcome;
        try {
            outcome = chair.synthesize(topic, List.copyOf(rounds), converged && !anyFailure);
        } catch (Exception e) {
            outcome = "(could not synthesize outcome: " + e.getMessage() + ")";
        }
        return new Discussion(topic, rounds, outcome, converged && !anyFailure);
    }
}
