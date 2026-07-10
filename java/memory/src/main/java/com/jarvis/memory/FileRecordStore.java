package com.jarvis.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Durable, append-only {@link RecordStore}: the flat-file half of the D1 storage abstraction.
 * Each collection is a plain text file under a directory, one record per line, tab-separated with a
 * URL-encoded payload — stdlib only, human-inspectable, safe for payloads containing tabs or
 * newlines.
 *
 * <p>Unlike {@link FileBackedStore}, which rewrites its whole file on every mutation, an append
 * here writes a single line with {@link StandardOpenOption#APPEND}, so audit logs and usage streams
 * stay cheap to grow. Reads scan the file, which is fine at personal-assistant scale; when a
 * collection outgrows that, the {@link RecordStore} seam lets a database-backed implementation take
 * over without touching callers.
 */
public final class FileRecordStore implements RecordStore {

    private final Path dir;
    private final ConcurrentMap<String, AtomicLong> nextSeq = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    /** Opens (or creates) a record store rooted at directory {@code dir}. */
    public FileRecordStore(Path dir) {
        this.dir = Objects.requireNonNull(dir, "dir");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create record store dir: " + dir, e);
        }
    }

    @Override
    public StoredRecord append(String collection, String payload) {
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(payload, "payload");
        synchronized (lockFor(collection)) {
            long seq = nextSeq.computeIfAbsent(
                    collection, c -> new AtomicLong(readAll(c).size())).getAndIncrement();
            StoredRecord record = new StoredRecord(seq, Instant.now(), payload);
            try {
                Files.writeString(fileFor(collection), encode(record) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to append to collection: " + collection, e);
            }
            return record;
        }
    }

    @Override
    public List<StoredRecord> list(String collection) {
        Objects.requireNonNull(collection, "collection");
        return readAll(collection);
    }

    @Override
    public List<StoredRecord> tail(String collection, int max) {
        if (max < 0) {
            throw new IllegalArgumentException("max must be >= 0, got " + max);
        }
        List<StoredRecord> all = readAll(collection);
        return max >= all.size() ? all : List.copyOf(all.subList(all.size() - max, all.size()));
    }

    @Override
    public long count(String collection) {
        return readAll(collection).size();
    }

    @Override
    public void clear(String collection) {
        Objects.requireNonNull(collection, "collection");
        synchronized (lockFor(collection)) {
            try {
                Files.deleteIfExists(fileFor(collection));
            } catch (IOException e) {
                throw new UncheckedIOException("failed to clear collection: " + collection, e);
            }
            nextSeq.remove(collection);
        }
    }

    private List<StoredRecord> readAll(String collection) {
        Objects.requireNonNull(collection, "collection");
        Path file = fileFor(collection);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<StoredRecord> records = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    records.add(decode(line));
                }
            }
            return records;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read collection: " + collection, e);
        }
    }

    private Object lockFor(String collection) {
        return locks.computeIfAbsent(collection, c -> new Object());
    }

    private Path fileFor(String collection) {
        return dir.resolve(enc(collection) + ".log");
    }

    private static String encode(StoredRecord record) {
        return record.seq() + "\t" + record.at() + "\t" + enc(record.payload());
    }

    private static StoredRecord decode(String line) {
        String[] fields = line.split("\t", -1);
        if (fields.length != 3) {
            throw new IllegalStateException("corrupt record line: " + line);
        }
        return new StoredRecord(Long.parseLong(fields[0]), Instant.parse(fields[1]), dec(fields[2]));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String dec(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
