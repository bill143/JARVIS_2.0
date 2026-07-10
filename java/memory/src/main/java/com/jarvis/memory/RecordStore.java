package com.jarvis.memory;

import java.util.List;

/**
 * Public contract for append-only record streams: the D1 storage abstraction. Where
 * {@link MemoryStore} is a keyed, overwrite-in-place map, a {@code RecordStore} is an ordered log —
 * records are appended once and read back in insertion order. It is the backing seam for
 * append-heavy productization data (audit log, usage metering, task/workflow run history).
 *
 * <p>Records are partitioned by {@code collection}, an isolation boundary analogous to a table.
 * Callers depend only on this interface; the flat-file implementation ({@link FileRecordStore}) can
 * be swapped for a database-backed one later without changing any call site.
 */
public interface RecordStore {

    /**
     * Appends {@code payload} to the end of {@code collection}.
     *
     * @return the stored record, carrying its assigned sequence and timestamp
     */
    StoredRecord append(String collection, String payload);

    /** Returns every record in {@code collection} in insertion order, or an empty list if none. */
    List<StoredRecord> list(String collection);

    /**
     * Returns the last {@code max} records of {@code collection} in insertion order (fewer if the
     * collection is smaller). {@code max} of 0 returns an empty list.
     */
    List<StoredRecord> tail(String collection, int max);

    /** Returns the number of records in {@code collection}. */
    long count(String collection);

    /** Removes every record in {@code collection}. */
    void clear(String collection);
}
