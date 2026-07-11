package com.jarvis.security;

import com.jarvis.tools.RiskTier;
import java.util.Objects;

/**
 * Decides, from a tool's {@link RiskTier} and the user's chosen {@link PermissionLevel}, whether an
 * action may run silently or must be confirmed. Read-only tools are never gated; unclassified
 * ({@link RiskTier#UNKNOWN}) tools are always prompted unless prompting is turned OFF — fail-safe by
 * default. The level is mutable so the Settings toggle takes effect immediately, live.
 */
public final class PermissionPolicy {

    private volatile PermissionLevel level;

    /** Creates a policy at the default level (prompt on destructive / unclassified only). */
    public PermissionPolicy() {
        this(PermissionLevel.DESTRUCTIVE);
    }

    public PermissionPolicy(PermissionLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    public PermissionLevel level() {
        return level;
    }

    public void setLevel(PermissionLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    /** Whether an action at {@code tier} must be confirmed under the current level. */
    public PermissionDecision decide(RiskTier tier) {
        if (level == PermissionLevel.OFF || tier == RiskTier.READ_ONLY) {
            return PermissionDecision.ALLOW;
        }
        if (tier == RiskTier.UNKNOWN) {
            return PermissionDecision.PROMPT;              // unclassified -> always confirm
        }
        if (level == PermissionLevel.MUTATING) {
            return PermissionDecision.PROMPT;              // mutating and destructive
        }
        // level == DESTRUCTIVE: gate destructive only, allow mutating tweaks
        return tier == RiskTier.DESTRUCTIVE ? PermissionDecision.PROMPT : PermissionDecision.ALLOW;
    }
}
