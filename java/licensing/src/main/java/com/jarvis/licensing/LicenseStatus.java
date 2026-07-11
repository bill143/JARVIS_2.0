package com.jarvis.licensing;

import java.util.Objects;

/**
 * The current licensing status, ready to show on the HUD.
 *
 * @param state where things stand
 * @param license the active license (present for LICENSED/EXPIRED), else null
 * @param message a short human-readable explanation
 */
public record LicenseStatus(LicenseState state, License license, String message) {

    public LicenseStatus {
        Objects.requireNonNull(state, "state");
        message = message == null ? "" : message;
    }

    public boolean isLocked() {
        return state.isLocked();
    }

    public static LicenseStatus dev() {
        return new LicenseStatus(LicenseState.DEV, null, "Development build — licensing not enforced.");
    }

    public static LicenseStatus unlicensed() {
        return new LicenseStatus(LicenseState.UNLICENSED, null,
                "No license found. Enter your license key to activate.");
    }

    public static LicenseStatus invalid(String why) {
        return new LicenseStatus(LicenseState.INVALID, null, why);
    }

    public static LicenseStatus licensed(License license) {
        return new LicenseStatus(LicenseState.LICENSED, license,
                "Licensed to " + license.licensee() + ".");
    }

    public static LicenseStatus expired(License license) {
        return new LicenseStatus(LicenseState.EXPIRED, license,
                "Your license expired. Please renew to continue.");
    }
}
