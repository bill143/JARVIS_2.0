package com.jarvis.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Durable {@link MemoryStore} for {@code String} values: the file-backed implementation deferred
 * in REQ-STEP-002, behind the same interface. Entries survive process restarts.
 *
 * <p>Storage is a plain text file, one entry per line, tab-separated with URL-encoded fields —
 * stdlib only, human-inspectable, safe for values containing tabs or newlines. Every mutation
 * rewrites the file atomically (temp file + move), which is plenty at personal-assistant scale.
 */
public final class FileBackedStore implements MemoryStore<String> {

    private static final String NO_METADATA = "-";

    private final Path file;
    private final ConcurrentMap<String, ConcurrentMap<String, MemoryEntry<String>>> byScope =
            new ConcurrentHashMap<>();

    /** Opens (or creates) the store at {@code file}, loading any existing entries. */
    public FileBackedStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        load();
    }

    @Override
    public MemoryEntry<String> put(String scope, String key, String value) {
        return put(scope, key, value, Map.of());
    }

    @Override
    public synchronized MemoryEntry<String> put(
            String scope, String key, String value, Map<String, String> metadata) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        MemoryEntry<String> entry = new MemoryEntry<>(
                UUID.randomUUID().toString(), scope, key, value, Instant.now(), metadata);
        byScope.computeIfAbsent(scope, s -> new ConcurrentHashMap<>()).put(key, entry);
        persist();
        return entry;
    }

    @Override
    public Optional<MemoryEntry<String>> get(String scope, String key) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        ConcurrentMap<String, MemoryEntry<String>> scoped = byScope.get(scope);
        return scoped == null ? Optional.empty() : Optional.ofNullable(scoped.get(key));
    }

    @Override
    public List<MemoryEntry<String>> query(String scope) {
        Objects.requireNonNull(scope, "scope");
        ConcurrentMap<String, MemoryEntry<String>> scoped = byScope.get(scope);
        return scoped == null ? List.of() : new ArrayList<>(scoped.values());
    }

    @Override
    public synchronized boolean delete(String scope, String key) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        ConcurrentMap<String, MemoryEntry<String>> scoped = byScope.get(scope);
        boolean removed = scoped != null && scoped.remove(key) != null;
        if (removed) {
            persist();
        }
        return removed;
    }

    @Override
    public synchronized void clear(String scope) {
        Objects.requireNonNull(scope, "scope");
        if (byScope.remove(scope) != null) {
            persist();
        }
    }

    @Override
    public synchronized void clear() {
        byScope.clear();
        persist();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                MemoryEntry<String> entry = decode(line);
                byScope.computeIfAbsent(entry.scope(), s -> new ConcurrentHashMap<>())
                        .put(entry.key(), entry);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load memory file: " + file, e);
        }
    }

    private void persist() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            List<String> lines = new ArrayList<>();
            for (ConcurrentMap<String, MemoryEntry<String>> scoped : byScope.values()) {
                for (MemoryEntry<String> entry : scoped.values()) {
                    lines.add(encode(entry));
                }
            }
            Files.write(temp, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException notAtomic) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist memory file: " + file, e);
        }
    }

    private static String encode(MemoryEntry<String> entry) {
        String metadata = entry.metadata().isEmpty()
                ? NO_METADATA
                : entry.metadata().entrySet().stream()
                        .map(kv -> enc(kv.getKey()) + "=" + enc(kv.getValue()))
                        .reduce((a, b) -> a + "&" + b).orElse(NO_METADATA);
        return String.join("\t",
                enc(entry.id()), enc(entry.scope()), enc(entry.key()),
                entry.createdAt().toString(), enc(entry.value()), metadata);
    }

    private static MemoryEntry<String> decode(String line) {
        String[] fields = line.split("\t", -1);
        if (fields.length != 6) {
            throw new IllegalStateException("corrupt memory line: " + line);
        }
        Map<String, String> metadata = new HashMap<>();
        if (!NO_METADATA.equals(fields[5])) {
            for (String pair : fields[5].split("&")) {
                int eq = pair.indexOf('=');
                metadata.put(dec(pair.substring(0, eq)), dec(pair.substring(eq + 1)));
            }
        }
        return new MemoryEntry<>(dec(fields[0]), dec(fields[1]), dec(fields[2]),
                dec(fields[4]), Instant.parse(fields[3]), metadata);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String dec(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
