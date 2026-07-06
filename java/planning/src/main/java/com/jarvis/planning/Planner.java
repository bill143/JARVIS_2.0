package com.jarvis.planning;

/**
 * The decomposition seam of the planning module: turns a goal into an ordered {@link Plan}.
 *
 * <p>This is where decomposition intelligence plugs in later — an LLM planning pass, heuristics, or
 * template expansion. The module ships the contract and the plan model, not a reasoning engine.
 */
@FunctionalInterface
public interface Planner {

    /** Decomposes {@code goal} into an ordered plan; must not return {@code null}. */
    Plan plan(String goal);
}
