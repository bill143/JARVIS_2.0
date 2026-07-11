package com.jarvis.licensing;

import java.time.Instant;
import java.util.Objects;

/**
 * The content of a license: who it's for, what edition, and when it was issued / expires. The
 * signature that proves the vendor issued it lives in the license <em>key</em> (see
 * {@link LicenseSigner} / {@link LicenseVerifier}), not here.
 *
 * @param licensee who the license is issued to
 * @param email contact email (may be blank)
 * @param edition product edition / plan (e.g. {@code "standard"})
 * @param issuedAt when it was issued
 * @param expiresAt when it lapses, or null for a perpetual license
 */
public record License(String licensee, String email, String edition, Instant issuedAt,
        Instant expiresAt) {

    public License {
        Objects.requireNonNull(licensee, "licensee");
        email = email == null ? "" : email;
        edition = edition == null || edition.isBlank() ? "standard" : edition;
        Objects.requireNonNull(issuedAt, "issuedAt");
        // expiresAt nullable = perpetual
    }

    /** Whether the license has lapsed as of {@code now}. Perpetual licenses never expire. */
    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }
}
