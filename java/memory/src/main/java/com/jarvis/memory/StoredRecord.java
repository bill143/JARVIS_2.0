package com.jarvis.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable, append-only record: one entry in a {@link RecordStore} collection.
 *
 * <p>Unlike {@link MemoryEntry} (a keyed value that can be overwritten), a stored record is never
 * updated — it is appended once and read back in insertion order. {@code seq} is a 0-based,
 * contiguous, monotonic position within its collection; {@code payload} is opaque to the store
 * (callers serialize their own domain model, typically JSON).
 *
 * @param seq 0-based position within the collection
 * @param at instant the record was appended
 * @param payload the caller's opaque, non-null content
 */
public record StoredRecord(long seq, Instant at, String payload) {

    public StoredRecord {
        if (seq < 0) {
            throw new IllegalArgumentException("seq must be >= 0, got " + seq);
        }
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(payload, "payload");
    }
}
