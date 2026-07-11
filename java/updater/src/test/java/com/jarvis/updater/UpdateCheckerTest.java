package com.jarvis.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UpdateCheckerTest {

    private static KeyPair keys;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keys = gen.generateKeyPair();
    }

    private static ManifestSource serving(String version) {
        return () -> ManifestSigner.sign(
                new UpdateManifest(version, "https://x/JARVIS-" + version + ".msi", "notes"),
                keys.getPrivate());
    }

    private UpdateChecker checker(ManifestSource source, ManifestVerifier verifier) {
        return new UpdateChecker(Version.parse("0.1.0"), source, verifier);
    }

    @Test
    void reportsAnAvailableUpdateWhenTheManifestIsNewerAndValid() {
        UpdateStatus s = checker(serving("0.2.0"), new ManifestVerifier(keys.getPublic())).check();
        assertEquals(UpdateState.UPDATE_AVAILABLE, s.state());
        assertNotNull(s.available());
        assertEquals("0.2.0", s.available().version());
        assertEquals("https://x/JARVIS-0.2.0.msi", s.available().downloadUrl());
    }

    @Test
    void reportsUpToDateWhenTheManifestIsNotNewer() {
        UpdateStatus s = checker(serving("0.1.0"), new ManifestVerifier(keys.getPublic())).check();
        assertEquals(UpdateState.UP_TO_DATE, s.state());
        assertNull(s.available());
    }

    @Test
    void aValidlyNewerButWRONGLYSIGNEDManifestIsIgnored() throws Exception {
        KeyPair attacker = KeyPairGenerator.getInstance("RSA").genKeyPair();
        ManifestSource forged = () -> ManifestSigner.sign(
                new UpdateManifest("9.9.9", "https://evil/x.msi", "pwn"), attacker.getPrivate());
        UpdateStatus s = checker(forged, new ManifestVerifier(keys.getPublic())).check();
        assertEquals(UpdateState.UNVERIFIED, s.state());
        assertNull(s.available());
    }

    @Test
    void fetchFailureIsANonFatalError() {
        ManifestSource down = () -> { throw new IOException("no network"); };
        UpdateStatus s = checker(down, new ManifestVerifier(keys.getPublic())).check();
        assertEquals(UpdateState.ERROR, s.state());
    }

    @Test
    void noSourceMeansDisabledAndLatestIsSafeBeforeChecking() {
        UpdateChecker c = checker(null, null);
        assertEquals(UpdateState.DISABLED, c.latest().state());   // safe default before any check
        assertEquals(UpdateState.DISABLED, c.check().state());
    }

    @Test
    void latestStartsAsCheckingWhenASourceExists() {
        UpdateChecker c = checker(serving("0.2.0"), new ManifestVerifier(keys.getPublic()));
        assertEquals(UpdateState.CHECKING, c.latest().state());   // before check() runs
    }
}
