package com.jarvis.agents;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs a small, bounded multi-agent conversation over a goal: a PLANNER drafts a plan, an EXECUTOR
 * carries it out, a CRITIC reviews it, and the EXECUTOR produces a refined final answer. Each role
 * is a turn through a pluggable {@link AgentTurn} (in production, the governed agent).
 *
 * <p>Hard-bounded: the pipeline is fixed and the total number of turns can never exceed
 * {@link #MAX_TURNS} — a budget guard so an autonomous conversation can't run away.
 */
public final class MultiAgentManager {

    /** Absolute ceiling on turns per run, regardless of configuration. */
    public static final int MAX_TURNS = 8;

    /** One agent turn: produce a response for {@code role} given {@code prompt}. */
    @FunctionalInterface
    public interface AgentTurn {
        String run(Role role, String prompt) throws Exception;
    }

    /** One message in the conversation. */
    public record Message(Role role, String content) {
    }

    /** The full conversation plus the final answer. */
    public record Conversation(String goal, List<Message> messages, String result) {
    }

    private final int maxTurns;

    public MultiAgentManager() {
        this(MAX_TURNS);
    }

    public MultiAgentManager(int maxTurns) {
        this.maxTurns = Math.min(Math.max(1, maxTurns), MAX_TURNS);
    }

    public Conversation run(String goal, AgentTurn turn) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(turn, "turn");
        List<Message> msgs = new ArrayList<>();
        int[] used = {0};

        String plan = step(turn, Role.PLANNER,
                "Goal: " + goal + "\nProduce a brief numbered plan.", msgs, used);
        String work = step(turn, Role.EXECUTOR,
                "Goal: " + goal + "\nPlan:\n" + plan + "\nCarry out the plan and report results.",
                msgs, used);
        String review = step(turn, Role.CRITIC,
                "Goal: " + goal + "\nResult:\n" + work
                        + "\nCritique briefly: is it complete and correct? Note any gaps.", msgs, used);
        String finalAnswer = step(turn, Role.EXECUTOR,
                "Using this critique:\n" + review + "\nGive the final answer for: " + goal, msgs, used);

        return new Conversation(goal, msgs, finalAnswer.isEmpty() ? work : finalAnswer);
    }

    /** Runs one turn if the budget allows; records it. Returns "" if the budget is spent. */
    private String step(AgentTurn turn, Role role, String prompt, List<Message> msgs, int[] used) {
        if (used[0] >= maxTurns) {
            return "";
        }
        used[0]++;
        String out;
        try {
            out = turn.run(role, prompt);
        } catch (Exception e) {
            out = "(error: " + e.getMessage() + ")";
        }
        if (out == null) {
            out = "";
        }
        msgs.add(new Message(role, out));
        return out;
    }
}
