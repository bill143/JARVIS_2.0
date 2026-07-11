package com.jarvis.updater;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The (unsigned) content of a published update: what version is available, where to download it,
 * and what changed. The signature that proves it came from the vendor is handled separately
 * ({@link ManifestSigner} / {@link ManifestVerifier}) so this stays a plain value.
 *
 * @param version the published version, e.g. {@code "0.2.0"}
 * @param downloadUrl where to fetch the installer
 * @param notes human-readable release notes
 */
public record UpdateManifest(String version, String downloadUrl, String notes) {

    public UpdateManifest {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(downloadUrl, "downloadUrl");
        notes = notes == null ? "" : notes;
    }

    /** The exact bytes a signature covers: the three fields, newline-separated, in a fixed order. */
    byte[] signingBytes() {
        return (version + "\n" + downloadUrl + "\n" + notes).getBytes(StandardCharsets.UTF_8);
    }
}
