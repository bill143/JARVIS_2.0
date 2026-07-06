package com.jarvis.agent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class PromptRouterTest {

    private static RouteMatcher contains(String keyword) {
        return prompt -> prompt.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private static PromptRouter<String> router() {
        return new PromptRouter<>(List.of(
                new Route<>("weather", contains("weather"), "weather-skill"),
                new Route<>("time", contains("time"), "clock-skill"),
                new Route<>("greeting", contains("hello"), "greeting-skill")));
    }

    @Test
    void selectsTheMatchingRoute() {
        Route<String> route = router().route("What is the weather in Boston?").orElseThrow();
        assertEquals("weather", route.name());
        assertEquals("weather-skill", route.target());
    }

    @Test
    void firstMatchWinsInRegistrationOrder() {
        // Prompt matches both "weather" and "time"; the earlier route must win.
        Route<String> route = router().route("weather at this time tomorrow").orElseThrow();
        assertEquals("weather", route.name());
    }

    @Test
    void noMatchReturnsEmpty() {
        assertTrue(router().route("play some music").isEmpty());
    }

    @Test
    void routeOrDefaultFallsBackWhenNothingMatches() {
        assertEquals("general-skill", router().routeOrDefault("play some music", "general-skill"));
        assertEquals("clock-skill", router().routeOrDefault("what time is it", "general-skill"));
    }

    @Test
    void emptyRouterMatchesNothing() {
        PromptRouter<String> empty = new PromptRouter<>(List.of());
        assertTrue(empty.route("anything").isEmpty());
    }

    @Test
    void duplicateRouteNamesAreRejected() {
        List<Route<String>> duplicated = List.of(
                new Route<>("same", contains("a"), "t1"),
                new Route<>("same", contains("b"), "t2"));
        assertThrows(IllegalArgumentException.class, () -> new PromptRouter<>(duplicated));
    }

    @Test
    void nullPromptIsRejected() {
        assertThrows(NullPointerException.class, () -> router().route(null));
    }

    @Test
    void routesSnapshotPreservesOrderAndIsImmutable() {
        PromptRouter<String> router = router();
        List<Route<String>> routes = router.routes();
        assertEquals(List.of("weather", "time", "greeting"),
                routes.stream().map(Route::name).toList());
        assertThrows(UnsupportedOperationException.class, () -> routes.remove(0));
    }

    @Test
    void routeComponentsRejectNulls() {
        assertThrows(NullPointerException.class, () -> new Route<>(null, contains("x"), "t"));
        assertThrows(NullPointerException.class, () -> new Route<String>("n", null, "t"));
        assertThrows(NullPointerException.class, () -> new Route<String>("n", contains("x"), null));
    }
}
