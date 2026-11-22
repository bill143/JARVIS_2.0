package com.jarvis.integrations.openhuman;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Authorization policy for durable writes into the OpenHuman shared brain (RFC 0001, §8 role
 * policy). Two rules, both enforced here <em>before</em> any network call:
 *
 * <ul>
 *   <li><b>Deny-by-default:</b> writes are refused entirely until explicitly enabled.</li>
 *   <li><b>Role-restricted:</b> even when enabled, only the {@code conductor} and explicitly
 *       authorized agent roles may write. Advisors are never authorized (they are read-only).</li>
 * </ul>
 *
 * <p>This is a separate layer from the risk-tier permission gate: the permission gate asks the
 * <em>user</em> to confirm a MUTATING action, while this policy governs <em>which agent role</em> is
 * even allowed to attempt a durable write. Both must pass.
 */
public final class MemoryWritePolicy {

    /** The conductor role is always authorized when writes are enabled. */
    public static final String CONDUCTOR = "conductor";

    private final boolean enabled;
    private final Set<String> authorizedRoles;

    /** The safe default: writes disabled (deny-by-default), no authorized roles. */
    public static MemoryWritePolicy denyAll() {
        return new MemoryWritePolicy(false, Set.of());
    }

    public MemoryWritePolicy(boolean enabled, Set<String> authorizedRoles) {
        this.enabled = enabled;
        this.authorizedRoles = new HashSet<>();
        this.authorizedRoles.add(CONDUCTOR);
        if (authorizedRoles != null) {
            for (String r : authorizedRoles) {
                if (r != null && !r.isBlank()) {
                    this.authorizedRoles.add(r.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    /** Whether durable writes are enabled at all. */
    public boolean enabled() {
        return enabled;
    }

    /** Whether {@code role} may perform a durable write right now. */
    public boolean mayWrite(String role) {
        if (!enabled) {
            return false;   // deny-by-default
        }
        if (role == null || role.isBlank()) {
            return false;
        }
        return authorizedRoles.contains(role.strip().toLowerCase(Locale.ROOT));
    }

    /** A short, value-free reason a write was refused (for the tool error + audit detail). */
    public String denialReason(String role) {
        if (!enabled) {
            return "durable writes are disabled (deny-by-default); enable them explicitly to allow";
        }
        return "role '" + role + "' is not authorized for durable writes";
    }
}
