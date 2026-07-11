package com.jarvis.licensing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * App-side verifier: confirms a license key was signed by the vendor's private key, using the
 * embedded public key, and decodes it into a {@link License}. Returns the license only when the
 * signature checks out — a forged, tampered, or malformed key yields {@link Optional#empty()}, so
 * the app never trusts a fake license. Expiry is <em>not</em> judged here (that needs the current
 * time); {@link LicenseManager} applies it.
 */
public final class LicenseVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Decoder B64 = Base64.getUrlDecoder();

    private final PublicKey publicKey;

    public LicenseVerifier(PublicKey publicKey) {
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
    }

    /** Builds a verifier from a Base64-encoded X.509 RSA public key. */
    public static LicenseVerifier fromBase64(String x509Base64) {
        Objects.requireNonNull(x509Base64, "x509Base64");
        try {
            byte[] der = Base64.getDecoder().decode(x509Base64.strip());
            PublicKey key = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
            return new LicenseVerifier(key);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid public key", e);
        }
    }

    /** The verified license if the key's signature is valid, otherwise empty. Never throws. */
    public Optional<License> verify(String licenseKey) {
        if (licenseKey == null) {
            return Optional.empty();
        }
        try {
            int dot = licenseKey.indexOf('.');
            if (dot <= 0 || dot == licenseKey.length() - 1) {
                return Optional.empty();
            }
            String segment = licenseKey.substring(0, dot);
            byte[] signature = B64.decode(licenseKey.substring(dot + 1));

            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(segment.getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(signature)) {
                return Optional.empty();
            }
            JsonNode p = MAPPER.readTree(new String(B64.decode(segment), StandardCharsets.UTF_8));
            String expires = p.path("expiresAt").isNull() ? null : p.path("expiresAt").asText(null);
            return Optional.of(new License(
                    p.path("licensee").asText(""),
                    p.path("email").asText(""),
                    p.path("edition").asText("standard"),
                    Instant.parse(p.path("issuedAt").asText()),
                    expires == null || expires.isBlank() ? null : Instant.parse(expires)));
        } catch (Exception e) {
            return Optional.empty();   // bad base64 / json / signature -> untrusted
        }
    }
}
