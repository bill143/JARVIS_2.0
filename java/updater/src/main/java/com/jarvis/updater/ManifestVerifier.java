package com.jarvis.updater;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * App-side verifier: parses a hosted manifest JSON and confirms it was signed by the vendor's
 * private key, using the embedded public key. Returns the manifest only when the signature checks
 * out — an unsigned, tampered, or wrongly-keyed manifest yields {@link Optional#empty()}, so the
 * updater never acts on content it can't trust.
 */
public final class ManifestVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PublicKey publicKey;

    public ManifestVerifier(PublicKey publicKey) {
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
    }

    /** Builds a verifier from a Base64-encoded X.509 RSA public key. */
    public static ManifestVerifier fromBase64(String x509Base64) {
        Objects.requireNonNull(x509Base64, "x509Base64");
        try {
            byte[] der = Base64.getDecoder().decode(x509Base64.strip());
            PublicKey key = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
            return new ManifestVerifier(key);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid public key", e);
        }
    }

    /** The verified manifest if the signature is valid, otherwise empty. Never throws. */
    public Optional<UpdateManifest> verify(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            UpdateManifest manifest = new UpdateManifest(
                    node.path("version").asText(""),
                    node.path("downloadUrl").asText(""),
                    node.path("notes").asText(""));
            if (manifest.version().isBlank() || manifest.downloadUrl().isBlank()) {
                return Optional.empty();
            }
            byte[] signature = Base64.getDecoder().decode(node.path("signature").asText(""));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(manifest.signingBytes());
            return verifier.verify(signature) ? Optional.of(manifest) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();   // malformed JSON / bad base64 / bad signature -> untrusted
        }
    }
}
