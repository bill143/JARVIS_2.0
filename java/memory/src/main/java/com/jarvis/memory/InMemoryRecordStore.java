package com.jarvis.memory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe, non-persistent {@link RecordStore}: the ephemeral counterpart to
 * {@link FileRecordStore}, for tests and transient use. Each collection is a
 * {@link CopyOnWriteArrayList}; there is no durability — records live for the lifetime of the
 * instance.
 */
public final class InMemoryRecordStore implements RecordStore {

    private final ConcurrentMap<String, CopyOnWriteArrayList<StoredRecord>> byCollection =
            new ConcurrentHashMap<>();

    @Override
    public StoredRecord append(String collection, String payload) {
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(payload, "payload");
        CopyOnWriteArrayList<StoredRecord> records =
                byCollection.computeIfAbsent(collection, c -> new CopyOnWriteArrayList<>());
        synchronized (records) {
            StoredRecord record = new StoredRecord(records.size(), Instant.now(), payload);
            records.add(record);
            return record;
        }
    }

    @Override
    public List<StoredRecord> list(String collection) {
        Objects.requireNonNull(collection, "collection");
        CopyOnWriteArrayList<StoredRecord> records = byCollection.get(collection);
        return records == null ? List.of() : List.copyOf(records);
    }

    @Override
    public List<StoredRecord> tail(String collection, int max) {
        if (max < 0) {
            throw new IllegalArgumentException("max must be >= 0, got " + max);
        }
        List<StoredRecord> all = list(collection);
        return max >= all.size() ? all : List.copyOf(all.subList(all.size() - max, all.size()));
    }

    @Override
    public long count(String collection) {
        Objects.requireNonNull(collection, "collection");
        CopyOnWriteArrayList<StoredRecord> records = byCollection.get(collection);
        return records == null ? 0 : records.size();
    }

    @Override
    public void clear(String collection) {
        Objects.requireNonNull(collection, "collection");
        byCollection.remove(collection);
    }
}
