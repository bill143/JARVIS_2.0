package com.jarvis.licensing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

/**
 * Vendor-side helper that turns a {@link License} into a signed license key the customer pastes in
 * to activate. The app never uses this — only the issuer, who keeps the private key secret. Kept
 * in-tree so issuing a key is a one-liner and so the sign/verify round-trip is testable.
 *
 * <p>Key format: {@code base64url(payloadJson) + "." + base64url(SHA256withRSA signature)} — a
 * compact, copy-pasteable token. The signature covers the payload segment's bytes.
 */
public final class LicenseSigner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private LicenseSigner() {
    }

    /** Signs {@code license} into a license key. */
    public static String sign(License license, PrivateKey privateKey) {
        Objects.requireNonNull(license, "license");
        Objects.requireNonNull(privateKey, "privateKey");
        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("licensee", license.licensee());
            payload.put("email", license.email());
            payload.put("edition", license.edition());
            payload.put("issuedAt", license.issuedAt().toString());
            payload.put("expiresAt", license.expiresAt() == null ? null : license.expiresAt().toString());
            String segment = B64.encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));

            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(segment.getBytes(StandardCharsets.US_ASCII));
            return segment + "." + B64.encodeToString(signer.sign());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("failed to sign license", e);
        }
    }
}
