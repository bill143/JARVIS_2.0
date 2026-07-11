package com.jarvis.security;

import com.jarvis.tools.RiskTier;

/**
 * Seam between "an action needs confirmation" and "the user answered". {@link AuthorizingTool} asks
 * a gate to confirm; the production gate ({@link PermissionBroker}) blocks until the browser
 * responds, while tests supply an instant-answering lambda.
 */
@FunctionalInterface
public interface PermissionGate {

    /**
     * Requests confirmation to run {@code tool} at {@code tier}. Blocks until the user answers or
     * the request times out.
     *
     * @param tool the tool name
     * @param tier its risk tier
     * @param detail a short, value-free summary of the call (e.g. argument names)
     * @return the user's decision
     */
    PermissionOutcome request(String tool, RiskTier tier, String detail);
}
