package com.jarvis.updater;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

/**
 * Vendor-side helper that signs an {@link UpdateManifest} with the private key and emits the signed
 * JSON that gets hosted. The app never uses this — only the publisher, who keeps the private key
 * secret. Kept in-tree so publishing a release is a one-liner and so the sign/verify round-trip is
 * testable.
 *
 * <p>Signature: {@code SHA256withRSA} over {@link UpdateManifest#signingBytes()}.
 */
public final class ManifestSigner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ManifestSigner() {
    }

    /** Signs {@code manifest} and returns the hosted JSON ({version, downloadUrl, notes, signature}). */
    public static String sign(UpdateManifest manifest, PrivateKey privateKey) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(privateKey, "privateKey");
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(manifest.signingBytes());
            String signature = Base64.getEncoder().encodeToString(signer.sign());
            ObjectNode node = MAPPER.createObjectNode();
            node.put("version", manifest.version());
            node.put("downloadUrl", manifest.downloadUrl());
            node.put("notes", manifest.notes());
            node.put("signature", signature);
            return node.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("failed to sign manifest", e);
        }
    }
}
