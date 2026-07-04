package com.jarvis.memory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Public contract for the memory module: a scope-partitioned key/value store for agent and task
 * context. Other modules depend on this interface, never on a concrete implementation.
 *
 * <p>Values are keyed by a {@code (scope, key)} pair. A scope is an isolation boundary: keys in one
 * scope never collide with or leak into another. Implementations decide durability; this contract
 * makes no persistence guarantee.
 *
 * @param <V> the type of stored values
 */
public interface MemoryStore<V> {

    /**
     * Stores {@code value} under {@code (scope, key)} with empty metadata. Any existing entry for
     * the same {@code (scope, key)} is overwritten.
     *
     * @return the stored entry
     */
    MemoryEntry<V> put(String scope, String key, V value);

    /**
     * Stores {@code value} under {@code (scope, key)} with the given metadata. Any existing entry
     * for the same {@code (scope, key)} is overwritten.
     *
     * @return the stored entry
     */
    MemoryEntry<V> put(String scope, String key, V value, Map<String, String> metadata);

    /**
     * Retrieves the entry stored under {@code (scope, key)}.
     *
     * @return the entry, or {@link Optional#empty()} if absent
     */
    Optional<MemoryEntry<V>> get(String scope, String key);

    /**
     * Returns a point-in-time snapshot of all entries in {@code scope}, or an empty list if the
     * scope holds no entries.
     */
    List<MemoryEntry<V>> query(String scope);

    /**
     * Removes the entry stored under {@code (scope, key)}.
     *
     * @return {@code true} if an entry was removed, {@code false} if none existed
     */
    boolean delete(String scope, String key);

    /** Removes every entry in {@code scope}. */
    void clear(String scope);

    /** Removes every entry in every scope. */
    void clear();
}
