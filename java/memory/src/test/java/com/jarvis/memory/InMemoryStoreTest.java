package com.jarvis.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InMemoryStoreTest {

    private MemoryStore<String> newStore() {
        return new InMemoryStore<>();
    }

    @Test
    void putGetRoundtrip() {
        MemoryStore<String> store = newStore();
        MemoryEntry<String> written = store.put("agent", "greeting", "hello");

        Optional<MemoryEntry<String>> read = store.get("agent", "greeting");
        assertTrue(read.isPresent());
        assertEquals("hello", read.get().value());
        assertEquals("agent", read.get().scope());
        assertEquals("greeting", read.get().key());
        assertEquals(written.id(), read.get().id());
        assertEquals(Map.of(), read.get().metadata());
    }

    @Test
    void putWithMetadataIsRetrievable() {
        MemoryStore<String> store = newStore();
        store.put("agent", "k", "v", Map.of("source", "test"));

        MemoryEntry<String> entry = store.get("agent", "k").orElseThrow();
        assertEquals("test", entry.metadata().get("source"));
    }

    @Test
    void getMissingKeyReturnsEmpty() {
        MemoryStore<String> store = newStore();
        assertTrue(store.get("agent", "absent").isEmpty());
        assertTrue(store.get("absent-scope", "k").isEmpty());
    }

    @Test
    void scopeIsolation() {
        MemoryStore<String> store = newStore();
        store.put("agent-a", "shared-key", "a-value");
        store.put("agent-b", "shared-key", "b-value");

        assertEquals("a-value", store.get("agent-a", "shared-key").orElseThrow().value());
        assertEquals("b-value", store.get("agent-b", "shared-key").orElseThrow().value());

        assertEquals(1, store.query("agent-a").size());
        assertEquals(1, store.query("agent-b").size());

        // Clearing one scope leaves the other untouched.
        store.clear("agent-a");
        assertTrue(store.get("agent-a", "shared-key").isEmpty());
        assertTrue(store.get("agent-b", "shared-key").isPresent());
    }

    @Test
    void overwriteReplacesValueAndAssignsNewIdentity() {
        MemoryStore<String> store = newStore();
        MemoryEntry<String> first = store.put("task", "state", "pending");
        MemoryEntry<String> second = store.put("task", "state", "done");

        assertEquals("done", store.get("task", "state").orElseThrow().value());
        assertEquals(1, store.query("task").size());
        assertNotEquals(first.id(), second.id());
    }

    @Test
    void deleteRemovesEntryAndReportsPriorPresence() {
        MemoryStore<String> store = newStore();
        store.put("task", "state", "pending");

        assertTrue(store.delete("task", "state"));
        assertTrue(store.get("task", "state").isEmpty());
        // Deleting again reports that nothing was present.
        assertFalse(store.delete("task", "state"));
        assertFalse(store.delete("absent-scope", "state"));
    }

    @Test
    void queryReturnsSnapshotAndClearEmptiesEverything() {
        MemoryStore<String> store = newStore();
        store.put("s1", "a", "1");
        store.put("s1", "b", "2");
        store.put("s2", "c", "3");

        assertEquals(2, store.query("s1").size());
        assertTrue(store.query("empty").isEmpty());

        store.clear();
        assertTrue(store.query("s1").isEmpty());
        assertTrue(store.query("s2").isEmpty());
    }

    @Test
    void concurrentAccessKeepsEveryDistinctWrite() throws InterruptedException {
        MemoryStore<String> store = newStore();
        int threads = 16;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        store.put("scope-" + threadId, "key-" + i, "v-" + threadId + "-" + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish in time");
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "pool did not terminate");

        for (int t = 0; t < threads; t++) {
            assertEquals(perThread, store.query("scope-" + t).size());
        }
    }

    @Test
    void concurrentWritesToSameKeyLeaveExactlyOneWinner() throws InterruptedException {
        MemoryStore<String> store = newStore();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<String> writtenValues = ConcurrentHashMap.newKeySet();

        for (int t = 0; t < threads; t++) {
            final String value = "writer-" + t;
            pool.execute(() -> {
                try {
                    start.await();
                    writtenValues.add(value);
                    store.put("contended", "key", value);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish in time");
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "pool did not terminate");

        // Exactly one entry survives, and its value is one of the writes (no corruption).
        assertEquals(1, store.query("contended").size());
        MemoryEntry<String> survivor = store.get("contended", "key").orElseThrow();
        assertTrue(writtenValues.contains(survivor.value()));
        assertEquals(new HashSet<>(writtenValues).size(), threads);
    }
}
