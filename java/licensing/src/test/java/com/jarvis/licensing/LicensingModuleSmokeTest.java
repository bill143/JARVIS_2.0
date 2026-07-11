package com.jarvis.licensing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LicensingModuleSmokeTest {

    @TempDir
    Path dir;

    @Test
    void endToEndSignActivateReload() throws Exception {
        var kp = KeyPairGenerator.getInstance("RSA").genKeyPair();
        String key = LicenseSigner.sign(
                new License("Acme", "a@x.com", "standard", Instant.parse("2026-01-01T00:00:00Z"), null),
                kp.getPrivate());
        EncryptedLicenseStore store = new EncryptedLicenseStore(dir.resolve("license.dat"));
        new LicenseManager(new LicenseVerifier(kp.getPublic()), store).activate(key);
        assertEquals(LicenseState.LICENSED,
                new LicenseManager(new LicenseVerifier(kp.getPublic()), store).status().state());
    }
}
