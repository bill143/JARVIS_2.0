package com.jarvis.licensing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LicenseManagerTest {

    private static KeyPair keys;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keys = gen.generateKeyPair();
    }

    @TempDir
    Path dir;

    private EncryptedLicenseStore store() {
        return new EncryptedLicenseStore(dir.resolve("license.dat"));
    }

    private LicenseVerifier verifier() {
        return new LicenseVerifier(keys.getPublic());
    }

    private String keyFor(Instant expiresAt) {
        return LicenseSigner.sign(
                new License("Bill", "b@x.com", "standard", Instant.parse("2026-01-01T00:00:00Z"),
                        expiresAt),
                keys.getPrivate());
    }

    @Test
    void noVerifierMeansDevModeUnlocked() {
        LicenseManager m = new LicenseManager(null, store());
        assertEquals(LicenseState.DEV, m.status().state());
        assertFalse(m.status().isLocked());
    }

    @Test
    void freshEnforcedInstallIsUnlicensedAndLocked() {
        LicenseManager m = new LicenseManager(verifier(), store());
        assertEquals(LicenseState.UNLICENSED, m.status().state());
        assertTrue(m.status().isLocked());
    }

    @Test
    void activatingAValidKeyLicensesAndPersists() {
        LicenseManager m = new LicenseManager(verifier(), store());
        assertEquals(LicenseState.LICENSED, m.activate(keyFor(null)).state());
        assertFalse(m.status().isLocked());

        // A brand-new manager over the same store stays licensed (persisted + reloaded).
        LicenseManager reopened = new LicenseManager(verifier(), store());
        assertEquals(LicenseState.LICENSED, reopened.status().state());
        assertEquals("Bill", reopened.status().license().licensee());
    }

    @Test
    void activatingAnInvalidKeyIsRejectedAndNotPersisted() {
        LicenseManager m = new LicenseManager(verifier(), store());
        assertEquals(LicenseState.INVALID, m.activate("totally.bogus").state());
        // Nothing was stored -> a reopened manager is still just unlicensed.
        assertEquals(LicenseState.UNLICENSED, new LicenseManager(verifier(), store()).status().state());
    }

    @Test
    void anExpiredLicenseIsReportedExpiredAndLocked() {
        // expires in 2026, but "now" is 2027 -> expired.
        LicenseManager m = new LicenseManager(verifier(), store(),
                () -> Instant.parse("2027-06-01T00:00:00Z"));
        LicenseStatus s = m.activate(keyFor(Instant.parse("2026-12-31T00:00:00Z")));
        assertEquals(LicenseState.EXPIRED, s.state());
        assertTrue(s.isLocked());
    }

    @Test
    void deactivateReturnsToUnlicensed() {
        LicenseManager m = new LicenseManager(verifier(), store());
        m.activate(keyFor(null));
        assertEquals(LicenseState.UNLICENSED, m.deactivate().state());
        assertEquals(LicenseState.UNLICENSED, new LicenseManager(verifier(), store()).status().state());
    }
}
