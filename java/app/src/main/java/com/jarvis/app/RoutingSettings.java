package com.jarvis.app;

/**
 * A typed, defaulted view over the Tier-2 routing configuration keys, resolved through
 * {@link ConnectorSettingsService} (saved value wins, else the environment variable, else the
 * documented default below). Pure parsing — this does not build or invoke a routing engine.
 *
 * <p>Every numeric field falls back to its default rather than throwing on an invalid or missing
 * value, so a bad/blank environment variable degrades to safe defaults instead of failing startup.
 * {@link #DEFAULT_OPENHUMAN_ENABLED} is {@code false} and {@link #DEFAULT_FAILOVER_ENABLED} is
 * {@code true} — Tier-2 routing itself only activates once OpenHuman is explicitly enabled.
 */
final class RoutingSettings {

    static final boolean DEFAULT_OPENHUMAN_ENABLED = false;
    static final boolean DEFAULT_FAILOVER_ENABLED = true;
    static final int DEFAULT_TIMEOUT_MS = 15_000;
    static final int DEFAULT_MAX_RETRIES = 1;
    static final int DEFAULT_BREAKER_FAIL_THRESHOLD = 5;
    static final int DEFAULT_BREAKER_WINDOW_SEC = 60;
    static final int DEFAULT_BREAKER_COOLDOWN_SEC = 30;

    /** A fully-resolved, typed snapshot of the routing configuration at the moment it was read. */
    record Snapshot(
            boolean openHumanEnabled,
            boolean failoverEnabled,
            int timeoutMs,
            int maxRetries,
            int breakerFailThreshold,
            int breakerWindowSec,
            int breakerCooldownSec) {
    }

    private final ConnectorSettingsService connectors;

    RoutingSettings(ConnectorSettingsService connectors) {
        this.connectors = connectors;
    }

    Snapshot snapshot() {
        return new Snapshot(
                bool("openhuman.enabled", "JARVIS_OPENHUMAN_ENABLED", DEFAULT_OPENHUMAN_ENABLED),
                bool("routing.failoverEnabled", "JARVIS_ROUTING_FAILOVER_ENABLED", DEFAULT_FAILOVER_ENABLED),
                positiveInt("routing.timeoutMs", "JARVIS_ROUTING_TIMEOUT_MS", DEFAULT_TIMEOUT_MS),
                nonNegativeInt("routing.maxRetries", "JARVIS_ROUTING_MAX_RETRIES", DEFAULT_MAX_RETRIES),
                positiveInt("routing.breakerFailThreshold", "JARVIS_ROUTING_BREAKER_FAIL_THRESHOLD",
                        DEFAULT_BREAKER_FAIL_THRESHOLD),
                positiveInt("routing.breakerWindowSec", "JARVIS_ROUTING_BREAKER_WINDOW_SEC",
                        DEFAULT_BREAKER_WINDOW_SEC),
                positiveInt("routing.breakerCooldownSec", "JARVIS_ROUTING_BREAKER_COOLDOWN_SEC",
                        DEFAULT_BREAKER_COOLDOWN_SEC));
    }

    private boolean bool(String key, String env, boolean fallback) {
        String v = connectors.resolve(key, env);
        return v == null ? fallback : Boolean.parseBoolean(v.strip());
    }

    private int positiveInt(String key, String env, int fallback) {
        int v = intOrFallback(key, env, fallback);
        return v > 0 ? v : fallback;
    }

    private int nonNegativeInt(String key, String env, int fallback) {
        int v = intOrFallback(key, env, fallback);
        return v >= 0 ? v : fallback;
    }

    private int intOrFallback(String key, String env, int fallback) {
        String v = connectors.resolve(key, env);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
