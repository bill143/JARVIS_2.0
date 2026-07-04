package com.jarvis.memory;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable record of a single stored memory.
 *
 * <p>Reimplements the agent/task context memory pattern (how context is stored, keyed, retrieved,
 * and scoped) as a domain-free scoped key/value entry. Intentionally carries no domain model:
 * {@code value} is generic so the store is reusable across modules.
 *
 * @param <V> the type of the stored value
 * @param id unique identifier for this entry
 * @param scope logical namespace the entry belongs to (isolation boundary)
 * @param key lookup key, unique within a scope
 * @param value the stored value
 * @param createdAt instant the entry was created
 * @param metadata immutable, non-null map of auxiliary attributes
 */
public record MemoryEntry<V>(
        String id,
        String scope,
        String key,
        V value,
        Instant createdAt,
        Map<String, String> metadata) {

    public MemoryEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(createdAt, "createdAt");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
