package com.jarvis.agent.routing;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, ordered prompt router: the skill/toolset dispatch pattern from
 * {@code NousResearch/hermes-agent}, reduced to its mechanism — match an incoming prompt against
 * available capabilities and select which one handles it.
 *
 * <p>Routes are consulted in registration order and the first match wins, so more specific routes
 * belong earlier in the list. Selection only: executing the target is the caller's job. Thread-safe
 * by immutability.
 *
 * @param <T> the type of the routing targets
 */
public final class PromptRouter<T> {

    private final List<Route<T>> routes;

    /**
     * @param routes ordered candidate routes; earlier routes take precedence. Route names must be
     *     unique.
     * @throws IllegalArgumentException if two routes share a name
     */
    public PromptRouter(List<Route<T>> routes) {
        this.routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
        long distinctNames = this.routes.stream().map(Route::name).distinct().count();
        if (distinctNames != this.routes.size()) {
            throw new IllegalArgumentException("route names must be unique");
        }
    }

    /** Returns the first route whose matcher accepts {@code prompt}, if any. */
    public Optional<Route<T>> route(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        return routes.stream().filter(r -> r.matcher().matches(prompt)).findFirst();
    }

    /**
     * Returns the target of the first matching route, or {@code fallback} when no route matches.
     */
    public T routeOrDefault(String prompt, T fallback) {
        Objects.requireNonNull(fallback, "fallback");
        return route(prompt).map(Route::target).orElse(fallback);
    }

    /** Returns a snapshot of the routes in consultation order. */
    public List<Route<T>> routes() {
        return routes;
    }
}
