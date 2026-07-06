package com.jarvis.agent.routing;

import java.util.Objects;

/**
 * A named binding from a {@link RouteMatcher} to the target that handles matching prompts.
 *
 * <p>The target is generic: a capability name, a {@code Tool}, an {@code AgentPolicy}, a pipeline —
 * routing selects, it does not execute.
 *
 * @param <T> the type of the routing target
 * @param name unique, human-readable name of the route
 * @param matcher decides whether a prompt belongs to this route
 * @param target what handles prompts routed here
 */
public record Route<T>(String name, RouteMatcher matcher, T target) {

    public Route {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(matcher, "matcher");
        Objects.requireNonNull(target, "target");
    }
}
