package com.jarvis.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe, non-persistent {@link MemoryStore} backed by {@link ConcurrentHashMap}.
 *
 * <p>Storage is partitioned by scope: an outer map keyed by scope holds an inner map keyed by entry
 * key. This gives scope isolation and per-key concurrency for free, without any external caching
 * library. There is no eviction and no durability — entries live for the lifetime of the instance.
 *
 * @param <V> the type of stored values
 */
public final class InMemoryStore<V> implements MemoryStore<V> {

    private final ConcurrentMap<String, ConcurrentMap<String, MemoryEntry<V>>> byScope =
            new ConcurrentHashMap<>();

    @Override
    public MemoryEntry<V> put(String scope, String key, V value) {
        return put(scope, key, value, Map.of());
    }

    @Override
    public MemoryEntry<V> put(String scope, String key, V value, Map<String, String> metadata) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        MemoryEntry<V> entry = new MemoryEntry<>(
                UUID.randomUUID().toString(), scope, key, value, Instant.now(), metadata);
        byScope.computeIfAbsent(scope, s -> new ConcurrentHashMap<>()).put(key, entry);
        return entry;
    }

    @Override
    public Optional<MemoryEntry<V>> get(String scope, String key) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        ConcurrentMap<String, MemoryEntry<V>> scoped = byScope.get(scope);
        return scoped == null ? Optional.empty() : Optional.ofNullable(scoped.get(key));
    }

    @Override
    public List<MemoryEntry<V>> query(String scope) {
        Objects.requireNonNull(scope, "scope");
        ConcurrentMap<String, MemoryEntry<V>> scoped = byScope.get(scope);
        return scoped == null ? List.of() : new ArrayList<>(scoped.values());
    }

    @Override
    public boolean delete(String scope, String key) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        ConcurrentMap<String, MemoryEntry<V>> scoped = byScope.get(scope);
        return scoped != null && scoped.remove(key) != null;
    }

    @Override
    public void clear(String scope) {
        Objects.requireNonNull(scope, "scope");
        byScope.remove(scope);
    }

    @Override
    public void clear() {
        byScope.clear();
    }
}
