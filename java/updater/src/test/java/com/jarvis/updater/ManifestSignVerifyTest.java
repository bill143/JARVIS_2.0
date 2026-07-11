package com.jarvis.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ManifestSignVerifyTest {

    private static KeyPair keys;
    private static KeyPair otherKeys;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keys = gen.generateKeyPair();
        otherKeys = gen.generateKeyPair();
    }

    private static final UpdateManifest MANIFEST =
            new UpdateManifest("0.2.0", "https://example.com/JARVIS-0.2.0.msi", "New things.");

    @Test
    void aSignedManifestVerifiesWithTheMatchingPublicKey() {
        String json = ManifestSigner.sign(MANIFEST, keys.getPrivate());
        Optional<UpdateManifest> verified = new ManifestVerifier(keys.getPublic()).verify(json);
        assertTrue(verified.isPresent());
        assertEquals("0.2.0", verified.get().version());
        assertEquals("https://example.com/JARVIS-0.2.0.msi", verified.get().downloadUrl());
    }

    @Test
    void verifierBuiltFromBase64PublicKeyWorks() {
        String json = ManifestSigner.sign(MANIFEST, keys.getPrivate());
        String b64 = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        assertTrue(ManifestVerifier.fromBase64(b64).verify(json).isPresent());
    }

    @Test
    void aDifferentKeyDoesNotVerify() {
        String json = ManifestSigner.sign(MANIFEST, keys.getPrivate());
        assertTrue(new ManifestVerifier(otherKeys.getPublic()).verify(json).isEmpty());
    }

    @Test
    void tamperingWithTheManifestBreaksVerification() {
        String json = ManifestSigner.sign(MANIFEST, keys.getPrivate())
                .replace("0.2.0", "9.9.9");   // attacker bumps the version
        assertTrue(new ManifestVerifier(keys.getPublic()).verify(json).isEmpty());
    }

    @Test
    void unsignedJsonDoesNotVerify() {
        String unsigned = "{\"version\":\"0.2.0\",\"downloadUrl\":\"https://x/y.msi\"}";
        assertTrue(new ManifestVerifier(keys.getPublic()).verify(unsigned).isEmpty());
    }
}
