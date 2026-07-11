package com.jarvis.licensing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LicenseSignVerifyTest {

    private static KeyPair keys;
    private static KeyPair otherKeys;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keys = gen.generateKeyPair();
        otherKeys = gen.generateKeyPair();
    }

    private static License perpetual() {
        return new License("Bill O'Neill", "bill@example.com", "standard",
                Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    @Test
    void aSignedKeyVerifiesAndDecodesTheLicense() {
        String key = LicenseSigner.sign(perpetual(), keys.getPrivate());
        Optional<License> out = new LicenseVerifier(keys.getPublic()).verify(key);
        assertTrue(out.isPresent());
        assertEquals("Bill O'Neill", out.get().licensee());
        assertEquals("standard", out.get().edition());
        assertNull(out.get().expiresAt());   // perpetual round-trips as null
    }

    @Test
    void expiryDateRoundTrips() {
        License dated = new License("X", "", "pro",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"));
        Optional<License> out = new LicenseVerifier(keys.getPublic())
                .verify(LicenseSigner.sign(dated, keys.getPrivate()));
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), out.get().expiresAt());
    }

    @Test
    void verifierFromBase64PublicKeyWorks() {
        String key = LicenseSigner.sign(perpetual(), keys.getPrivate());
        String b64 = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        assertTrue(LicenseVerifier.fromBase64(b64).verify(key).isPresent());
    }

    @Test
    void aKeySignedByAnotherPartyIsRejected() {
        String forged = LicenseSigner.sign(perpetual(), otherKeys.getPrivate());
        assertTrue(new LicenseVerifier(keys.getPublic()).verify(forged).isEmpty());
    }

    @Test
    void tamperingWithThePayloadBreaksVerification() {
        String key = LicenseSigner.sign(perpetual(), keys.getPrivate());
        // Flip a character in the payload segment.
        String tampered = "A" + key.substring(1);
        assertTrue(new LicenseVerifier(keys.getPublic()).verify(tampered).isEmpty());
    }

    @Test
    void garbageKeysAreRejectedNotThrown() {
        LicenseVerifier v = new LicenseVerifier(keys.getPublic());
        assertTrue(v.verify(null).isEmpty());
        assertTrue(v.verify("").isEmpty());
        assertTrue(v.verify("no-dot").isEmpty());
        assertTrue(v.verify("bad.").isEmpty());
    }
}
