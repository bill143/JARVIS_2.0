package com.jarvis.discussion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A bounded, project-scoped discussion loop: a {@link Chair} (JARVIS) puts questions to an
 * {@link Advisor} (e.g. OpenHuman) for a few rounds, then synthesizes an outcome. The chair drives
 * and decides — the advisor only contributes — so the hierarchy is "JARVIS chairs, the advisor
 * advises". The loop is hard-bounded ({@link #MAX_ROUNDS}) so two models cannot talk forever.
 *
 * <p>An advisor call that fails is recorded as an explicit error round and ends the discussion as
 * <em>not converged</em> — never silently treated as "nothing to add".
 *
 * <p>{@link #runWithConsensus} is an entirely additive extension (see that method's Javadoc):
 * {@link #run} above is untouched, so legacy discussions behave byte-for-byte as before regardless
 * of anything below.
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

    /**
     * Casts one agent's vote on whether the discussion (as of {@code soFar}) is settled. Used only
     * by {@link #runWithConsensus} — legacy {@link #run} never calls this.
     */
    @FunctionalInterface
    public interface VoteCaster {
        ConsensusVote castVote(String agentId, String topic, List<Round> soFar, int round)
                throws Exception;
    }

    /**
     * The result of {@link #runWithConsensus}: the transcript/outcome (same shape as a plain
     * {@link Discussion}), the last consensus evaluation, and whether the outcome is actually
     * finalized (actionable). {@code finalized} is {@code true} only when {@code consensus.achieved()}
     * is {@code true} — a discussion that exhausted its round budget, or where the chair converged
     * without the advisor's concurring vote, fails closed: the transcript/outcome text still exist
     * (for visibility), but {@code finalized=false} means nothing should act on it.
     */
    public record ConsensusDiscussion(Discussion discussion, ConsensusResult consensus,
            boolean finalized) {
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

    /**
     * Runs the same bounded chair/advisor loop as {@link #run}, but gated by a consensus vote after
     * every round. With {@link ConsensusPolicy#mode()} == {@link ConsensusMode#OFF}, this delegates
     * straight to {@link #run} (wrapped in a trivially-achieved {@link ConsensusDiscussion}) — so
     * disabling consensus reproduces the legacy path exactly, not an approximation of it.
     *
     * <p>With {@link ConsensusMode#UNANIMOUS}: after each successful Q&amp;A round, {@code voteCaster}
     * is asked to cast a vote for every id in {@code expectedAgentIds} (a failed or slow — beyond
     * {@link ConsensusPolicy#timeoutMs()}, when {@code > 0} — vote cast counts as a missing vote, per
     * {@link ConsensusGate}'s "missing votes block unanimity" rule), the accumulated votes are
     * evaluated via {@link ConsensusGate}, and the loop stops as soon as consensus is achieved. If the
     * round budget ({@code min(policy.maxRounds(), MAX_ROUNDS)}) is exhausted first — or the chair
     * converges, or the advisor errors, before consensus is achieved — the discussion still produces a
     * transcript/outcome for visibility, but {@link ConsensusDiscussion#finalized()} is {@code false}:
     * fail closed, no final action.
     */
    public ConsensusDiscussion runWithConsensus(String topic, Chair chair, Advisor advisor,
            ConsensusPolicy policy, Set<String> expectedAgentIds, VoteCaster voteCaster) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(chair, "chair");
        Objects.requireNonNull(advisor, "advisor");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(expectedAgentIds, "expectedAgentIds");
        Objects.requireNonNull(voteCaster, "voteCaster");

        if (policy.mode() == ConsensusMode.OFF) {
            Discussion d = run(topic, chair, advisor);
            return new ConsensusDiscussion(d,
                    new ConsensusResult(true, List.of(), List.of(), "consensus disabled"), true);
        }

        int bound = Math.min(Math.max(1, policy.maxRounds()), MAX_ROUNDS);
        ConsensusGate gate = new ConsensusGate();
        List<Round> rounds = new ArrayList<>();
        List<ConsensusVote> allVotes = new ArrayList<>();
        ConsensusResult lastResult =
                new ConsensusResult(false, List.of(), List.copyOf(expectedAgentIds), "no rounds attempted");
        boolean chairConverged = false;

        for (int i = 1; i <= bound; i++) {
            String question;
            try {
                question = chair.next(topic, List.copyOf(rounds));
            } catch (Exception e) {
                rounds.add(new Round(i, null, null, "chair error: " + e.getMessage()));
                break;
            }
            if (question == null || question.strip().startsWith(CONVERGED_MARKER)) {
                chairConverged = true;
                break;
            }
            try {
                String answer = advisor.respond(question);
                rounds.add(new Round(i, question, answer, null));
            } catch (Exception e) {
                rounds.add(new Round(i, question, null, "advisor error: " + e.getMessage()));
                break;
            }

            List<ConsensusVote> roundVotes = new ArrayList<>();
            List<Round> soFar = List.copyOf(rounds);
            for (String agentId : expectedAgentIds) {
                try {
                    roundVotes.add(castVoteBounded(voteCaster, agentId, topic, soFar, i,
                            policy.timeoutMs()));
                } catch (Exception e) {
                    // A failed/slow vote counts as a missing vote — ConsensusGate blocks on that,
                    // it never silently passes. Nothing to record here; absence is the signal.
                }
            }
            allVotes.addAll(roundVotes);
            lastResult = gate.evaluate(policy, allVotes, expectedAgentIds, i);
            if (lastResult.achieved()) {
                break;
            }
        }

        boolean anyFailure = rounds.stream().anyMatch(Round::failed);
        boolean finalized = lastResult.achieved() && !anyFailure;
        String outcome;
        try {
            outcome = chair.synthesize(topic, List.copyOf(rounds), finalized);
        } catch (Exception e) {
            outcome = "(could not synthesize outcome: " + e.getMessage() + ")";
        }
        Discussion discussion = new Discussion(topic, rounds, outcome, finalized);
        return new ConsensusDiscussion(discussion, lastResult, finalized);
    }

    /** Casts one vote bounded by {@code timeoutMs} (a non-positive value means no bound at all). */
    private static ConsensusVote castVoteBounded(VoteCaster voteCaster, String agentId, String topic,
            List<Round> soFar, int round, long timeoutMs) throws Exception {
        if (timeoutMs <= 0) {
            return voteCaster.castVote(agentId, topic, soFar, round);
        }
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ConsensusVote> f = ex.submit(() -> voteCaster.castVote(agentId, topic, soFar, round));
            try {
                return f.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                f.cancel(true);
                throw te;
            }
        }
    }
}
