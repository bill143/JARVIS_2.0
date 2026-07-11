package com.jarvis.security;

import com.jarvis.tools.RiskTier;
import java.util.Objects;

/**
 * A confirmation request currently awaiting the user's answer. The browser polls these and renders
 * a prompt for each.
 *
 * @param id opaque handle used to answer this specific request
 * @param tool the tool awaiting approval
 * @param riskTier its risk tier
 * @param detail a short, value-free summary of the call
 * @param requestedAtMillis when it was raised (epoch millis)
 */
public record PendingPermission(String id, String tool, RiskTier riskTier, String detail,
        long requestedAtMillis) {

    public PendingPermission {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tool, "tool");
        riskTier = riskTier == null ? RiskTier.UNKNOWN : riskTier;
        detail = detail == null ? "" : detail;
    }
}
