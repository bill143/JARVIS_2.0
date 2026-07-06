package com.jarvis.agent.routing;

/**
 * The matching seam of prompt routing: decides whether a prompt belongs to a route.
 *
 * <p>Implementations range from trivial (keyword, regex) to model-backed (embedding similarity,
 * LLM intent classification) in later steps — the router only needs the boolean.
 */
@FunctionalInterface
public interface RouteMatcher {

    /** Returns whether {@code prompt} should be handled by the route owning this matcher. */
    boolean matches(String prompt);
}
