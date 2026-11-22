package com.jarvis.app;

import com.jarvis.discussion.ConsensusMode;
import com.jarvis.discussion.ConsensusPolicy;
import java.util.Locale;

/**
 * A typed, defaulted view over the discussion-consensus configuration keys, resolved through
 * {@link ConnectorSettingsService} (saved value wins, else the environment variable, else the
 * documented default below) — the same pattern {@link RoutingSettings} uses for Tier-2 routing.
 *
 * <p>{@link #DEFAULT_ENABLED} is {@code false}: consensus is fully inert unless explicitly turned
 * on. {@link #effectivePolicy} is the single choke point that enforces default-deny for per-request
 * overrides — a request cannot enable consensus (or otherwise get a non-OFF policy) unless the
 * global switch is also on, matching every other safety-gated feature in this app
 * ({@code GatedLaneService}, {@code OpenHumanWriteGate}).
 */
final class ConsensusSettings {

    static final boolean DEFAULT_ENABLED = false;
    static final ConsensusMode DEFAULT_MODE = ConsensusMode.OFF;
    static final int DEFAULT_MAX_ROUNDS = 3;
    static final boolean DEFAULT_REQUIRE_RATIONALE = true;
    static final long DEFAULT_TIMEOUT_MS = 5000L;

    /** A fully-resolved, typed snapshot of the consensus configuration at the moment it was read. */
    record Snapshot(boolean enabled, ConsensusMode mode, int maxRounds, boolean requireRationale,
            long timeoutMs) {

        /** The policy this snapshot implies on its own, with no request-level override applied. */
        ConsensusPolicy policy() {
            return enabled ? new ConsensusPolicy(mode, maxRounds, requireRationale, timeoutMs)
                    : ConsensusPolicy.off();
        }
    }

    private final ConnectorSettingsService connectors;

    ConsensusSettings(ConnectorSettingsService connectors) {
        this.connectors = connectors;
    }

    Snapshot snapshot() {
        return new Snapshot(
                bool("consensus.enabled", "JARVIS_CONSENSUS_ENABLED", DEFAULT_ENABLED),
                mode("consensus.mode", "JARVIS_CONSENSUS_MODE", DEFAULT_MODE),
                positiveInt("consensus.maxRounds", "JARVIS_CONSENSUS_MAX_ROUNDS", DEFAULT_MAX_ROUNDS),
                bool("consensus.requireRationale", "JARVIS_CONSENSUS_REQUIRE_RATIONALE",
                        DEFAULT_REQUIRE_RATIONALE),
                nonNegativeLong("consensus.timeoutMs", "JARVIS_CONSENSUS_TIMEOUT_MS",
                        DEFAULT_TIMEOUT_MS));
    }

    /**
     * The policy that actually governs one discussion run. {@code requestOverride} (nullable) is
     * honored <b>only</b> when the global switch is on; when it's off, the override is ignored
     * entirely and {@link ConsensusPolicy#off()} is returned regardless of what was requested —
     * default-deny, not "whichever is more permissive".
     */
    ConsensusPolicy effectivePolicy(ConsensusPolicy requestOverride) {
        Snapshot snap = snapshot();
        if (!snap.enabled()) {
            return ConsensusPolicy.off();
        }
        return requestOverride != null ? requestOverride : snap.policy();
    }

    private boolean bool(String key, String env, boolean fallback) {
        String v = connectors.resolve(key, env);
        return v == null ? fallback : Boolean.parseBoolean(v.strip());
    }

    private ConsensusMode mode(String key, String env, ConsensusMode fallback) {
        String v = connectors.resolve(key, env);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return ConsensusMode.valueOf(v.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private int positiveInt(String key, String env, int fallback) {
        String v = connectors.resolve(key, env);
        if (v == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(v.strip());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private long nonNegativeLong(String key, String env, long fallback) {
        String v = connectors.resolve(key, env);
        if (v == null) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(v.strip());
            return parsed >= 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
