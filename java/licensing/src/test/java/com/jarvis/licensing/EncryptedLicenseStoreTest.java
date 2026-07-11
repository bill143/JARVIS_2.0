package com.jarvis.licensing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptedLicenseStoreTest {

    @TempDir
    Path dir;

    private Path datFile() {
        return dir.resolve("license.dat");
    }

    @Test
    void roundTripsThroughEncryptionAndSurvivesReopen() {
        new EncryptedLicenseStore(datFile()).save("payload.signature");
        // A fresh instance over the same file (simulated restart) reads it back.
        assertEquals("payload.signature", new EncryptedLicenseStore(datFile()).load().orElseThrow());
    }

    @Test
    void theFileOnDiskIsNotPlaintext() throws Exception {
        new EncryptedLicenseStore(datFile()).save("SECRET-LICENSE-KEY");
        String raw = new String(Files.readAllBytes(datFile()));
        assertFalse(raw.contains("SECRET-LICENSE-KEY"));   // opaque on disk
    }

    @Test
    void missingFileLoadsAsEmpty() {
        assertTrue(new EncryptedLicenseStore(dir.resolve("nope.dat")).load().isEmpty());
    }

    @Test
    void corruptFileLoadsAsEmptyNotAnError() throws Exception {
        Files.write(datFile(), new byte[] {1, 2, 3, 4, 5});   // too short / not valid ciphertext
        assertTrue(new EncryptedLicenseStore(datFile()).load().isEmpty());
    }

    @Test
    void clearRemovesTheStoredLicense() {
        EncryptedLicenseStore store = new EncryptedLicenseStore(datFile());
        store.save("k");
        store.clear();
        assertTrue(store.load().isEmpty());
    }
}
