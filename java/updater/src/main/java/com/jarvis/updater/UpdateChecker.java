package com.jarvis.updater;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the startup update check: fetch the manifest, verify its signature, compare versions. It is
 * deliberately conservative and non-fatal — a missing source, an unverifiable manifest, or a fetch
 * error each yields a benign status rather than an exception, so the check never disrupts startup.
 * Nothing here downloads or installs anything; it only reports.
 *
 * <p>Fail-safe posture: an update is reported <em>only</em> when a manifest is both signature-valid
 * and strictly newer than the running version.
 */
public final class UpdateChecker {

    private final Version current;
    private final ManifestSource source;         // null = checks disabled
    private final ManifestVerifier verifier;     // null = can't verify
    private final AtomicReference<UpdateStatus> latest;

    /**
     * @param current the running version
     * @param source where to fetch the manifest, or null to disable checks
     * @param verifier the signature verifier, or null if no public key is configured
     */
    public UpdateChecker(Version current, ManifestSource source, ManifestVerifier verifier) {
        this.current = Objects.requireNonNull(current, "current");
        this.source = source;
        this.verifier = verifier;
        this.latest = new AtomicReference<>(
                source == null ? UpdateStatus.disabled(current) : UpdateStatus.checking(current));
    }

    /** Performs the check, caches the result for {@link #latest()}, and returns it. */
    public UpdateStatus check() {
        UpdateStatus status = compute();
        latest.set(status);
        return status;
    }

    /** The most recent result (a benign {@code CHECKING}/{@code DISABLED} status before the first check). */
    public UpdateStatus latest() {
        return latest.get();
    }

    private UpdateStatus compute() {
        if (source == null) {
            return UpdateStatus.disabled(current);
        }
        String json;
        try {
            json = source.fetch();
        } catch (IOException e) {
            return UpdateStatus.error(current, "Update check failed: " + e.getMessage());
        }
        if (verifier == null) {
            return UpdateStatus.unverified(current, "No update public key is configured.");
        }
        Optional<UpdateManifest> verified = verifier.verify(json);
        if (verified.isEmpty()) {
            return UpdateStatus.unverified(current, "Update manifest failed signature verification.");
        }
        UpdateManifest manifest = verified.get();
        Version published;
        try {
            published = Version.parse(manifest.version());
        } catch (RuntimeException e) {
            return UpdateStatus.error(current, "Manifest has an unreadable version.");
        }
        return published.isNewerThan(current)
                ? UpdateStatus.available(current, manifest)
                : UpdateStatus.upToDate(current);
    }
}
