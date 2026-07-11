package com.jarvis.tools;

/**
 * How dangerous a tool invocation is. The shared risk vocabulary for Phase 1 governance: plugin
 * manifests declare a tool's tier, the permission layer decides whether to prompt based on it, and
 * the audit log records it.
 *
 * <p>Ordered from safest to most dangerous so callers can compare with {@link #atLeast}.
 */
public enum RiskTier {

    /** Reads state only; safe to run without asking (clock, weather, list mail). */
    READ_ONLY,

    /** Changes state but is reversible / non-destructive (set volume, create a calendar event). */
    MUTATING,

    /** Irreversible or high-impact (delete mail, shut down the PC, send a message). */
    DESTRUCTIVE,

    /** Tier not yet declared (no manifest). Treated conservatively by the permission layer. */
    UNKNOWN;

    /** Whether this tier is at least as dangerous as {@code other} (UNKNOWN never compares true). */
    public boolean atLeast(RiskTier other) {
        return this != UNKNOWN && other != UNKNOWN && ordinal() >= other.ordinal();
    }
}
