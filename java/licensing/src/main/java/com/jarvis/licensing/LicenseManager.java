package com.jarvis.licensing;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The licensing entry point: on construction it loads any stored license and computes the current
 * {@link LicenseStatus}; thereafter {@link #activate} / {@link #deactivate} update it. Fail-safe and
 * graceful — an invalid or missing license produces a locked <em>status</em>, never an exception, so
 * the agent loop is never disrupted.
 *
 * <p>When no verifier is supplied (no embedded public key — the owner's own build), the manager is
 * in {@link LicenseState#DEV}: enforcement is off and the app is unlocked.
 */
public final class LicenseManager {

    private final LicenseVerifier verifier;      // null = DEV (enforcement off)
    private final EncryptedLicenseStore store;
    private final Supplier<Instant> clock;
    private volatile LicenseStatus status;

    public LicenseManager(LicenseVerifier verifier, EncryptedLicenseStore store) {
        this(verifier, store, Instant::now);
    }

    public LicenseManager(LicenseVerifier verifier, EncryptedLicenseStore store,
            Supplier<Instant> clock) {
        this.verifier = verifier;
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.status = evaluateFromStore();
    }

    /** The current status (recomputed only on activate/deactivate). */
    public LicenseStatus status() {
        return status;
    }

    /** Verifies a pasted license key and, if valid, persists it. Returns the new status. */
    public synchronized LicenseStatus activate(String licenseKey) {
        if (verifier == null) {
            return status = LicenseStatus.dev();
        }
        Optional<License> license = verifier.verify(licenseKey);
        if (license.isEmpty()) {
            return status = LicenseStatus.invalid("That license key is not valid.");
        }
        store.save(licenseKey);
        return status = statusFor(license.get());
    }

    /** Removes the stored license. Returns the new (locked, unless DEV) status. */
    public synchronized LicenseStatus deactivate() {
        store.clear();
        return status = (verifier == null) ? LicenseStatus.dev() : LicenseStatus.unlicensed();
    }

    private LicenseStatus evaluateFromStore() {
        if (verifier == null) {
            return LicenseStatus.dev();
        }
        Optional<String> key = store.load();
        if (key.isEmpty()) {
            return LicenseStatus.unlicensed();
        }
        return verifier.verify(key.get())
                .map(this::statusFor)
                .orElse(LicenseStatus.unlicensed());   // stored key no longer verifies
    }

    private LicenseStatus statusFor(License license) {
        return license.isExpired(clock.get())
                ? LicenseStatus.expired(license)
                : LicenseStatus.licensed(license);
    }
}
