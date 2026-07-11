package com.jarvis.registry;

import com.jarvis.tools.RiskTier;
import java.util.Objects;

/**
 * An immutable snapshot of a tool's registry state: its risk tier plus live health metrics. This is
 * what the Tools &amp; Skills view renders.
 *
 * @param name tool name
 * @param riskTier declared risk tier (UNKNOWN if unmanifested)
 * @param health current derived health
 * @param totalCalls invocations recorded
 * @param failures invocations that failed
 * @param consecutiveFailures failures since the last success
 * @param lastError the most recent error message, or "" if none
 */
public record ToolStats(String name, RiskTier riskTier, ToolHealth health,
        long totalCalls, long failures, int consecutiveFailures, String lastError) {

    public ToolStats {
        Objects.requireNonNull(name, "name");
        riskTier = riskTier == null ? RiskTier.UNKNOWN : riskTier;
        health = health == null ? ToolHealth.OPERATIONAL : health;
        lastError = lastError == null ? "" : lastError;
    }
}
