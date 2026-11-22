package com.jarvis.app;

/**
 * Opt-in feature switches for the Phase-1 proactive capabilities. Both default to <b>OFF</b>: the
 * proactive greeting and the research endpoint do nothing until explicitly enabled in the
 * environment, so a default install never greets or researches on its own.
 *
 * @param presenceEnabled {@code JARVIS_PRESENCE_ENABLED=true} turns on proactive greeting
 * @param researchEnabled {@code JARVIS_RESEARCH_ENABLED=true} turns on the research endpoint
 */
record FeatureFlags(boolean presenceEnabled, boolean researchEnabled) {

    static FeatureFlags fromEnvironment() {
        return new FeatureFlags(flag("JARVIS_PRESENCE_ENABLED"), flag("JARVIS_RESEARCH_ENABLED"));
    }

    private static boolean flag(String name) {
        return "true".equalsIgnoreCase(String.valueOf(System.getenv(name)).strip());
    }
}
