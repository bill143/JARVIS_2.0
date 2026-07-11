package com.jarvis.licensing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stores the activated license key in an encrypted {@code license.dat} (AES-256/GCM). The file
 * layout is {@code [12-byte IV][ciphertext+tag]}, written atomically.
 *
 * <p>Honest limit: this is symmetric encryption with a key derived from an embedded secret, so it
 * stops casual reading/tampering of the license file — not a determined attacker who patches the
 * bytecode. That matches the offline‑licensing goal (keep honest buyers honest); real revocation
 * needs online activation, deferred.
 */
public final class EncryptedLicenseStore {

    // Embedded secret -> AES key. Not a real secret against a decompiler; keeps the file opaque.
    private static final String SECRET = "JARVIS-license-store-v1/do-not-tamper";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Path file;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public EncryptedLicenseStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(SECRET.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");   // 256-bit
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Encrypts and writes {@code licenseKey}, replacing any previous file. */
    public void save(String licenseKey) {
        Objects.requireNonNull(licenseKey, "licenseKey");
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(licenseKey.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, out);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException notAtomic) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write license file", e);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("failed to encrypt license", e);
        }
    }

    /** Reads and decrypts the stored license key, or empty if absent/unreadable/corrupt. */
    public Optional<String> load() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            byte[] all = Files.readAllBytes(file);
            if (all.length <= IV_BYTES) {
                return Optional.empty();
            }
            byte[] iv = Arrays.copyOfRange(all, 0, IV_BYTES);
            byte[] ct = Arrays.copyOfRange(all, IV_BYTES, all.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return Optional.of(new String(cipher.doFinal(ct), StandardCharsets.UTF_8));
        } catch (IOException | java.security.GeneralSecurityException | RuntimeException e) {
            return Optional.empty();   // tampered / wrong key / truncated -> treat as no license
        }
    }

    /** Removes the stored license (deactivation). */
    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to remove license file", e);
        }
    }
}
