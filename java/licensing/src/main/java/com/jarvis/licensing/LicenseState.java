package com.jarvis.licensing;

/**
 * Where licensing stands.
 *
 * <ul>
 *   <li>{@link #DEV} — no license public key is embedded, so enforcement is off (the owner's own
 *       build). Treated as unlocked.</li>
 *   <li>{@link #LICENSED} — a valid, unexpired license is active.</li>
 *   <li>{@link #UNLICENSED} — enforcement is on but no license is present.</li>
 *   <li>{@link #EXPIRED} — a valid license that has lapsed.</li>
 *   <li>{@link #INVALID} — a license key was supplied but failed signature verification.</li>
 * </ul>
 */
public enum LicenseState {
    DEV,
    LICENSED,
    UNLICENSED,
    EXPIRED,
    INVALID;

    /** Whether the app should present its locked state for this status. */
    public boolean isLocked() {
        return this == UNLICENSED || this == EXPIRED || this == INVALID;
    }
}
