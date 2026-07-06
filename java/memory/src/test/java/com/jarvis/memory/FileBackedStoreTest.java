package com.jarvis.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBackedStoreTest {

    @TempDir
    Path dir;

    private Path storeFile() {
        return dir.resolve("memory.tsv");
    }

    @Test
    void entriesSurviveARestart() {
        FileBackedStore first = new FileBackedStore(storeFile());
        first.put("reminders", "reminder-0", "call the supplier", Map.of("when", "9am"));
        first.put("session", "turn-0", "hello -> hi");

        // Simulate a restart: a brand-new instance over the same file.
        FileBackedStore second = new FileBackedStore(storeFile());
        MemoryEntry<String> reminder = second.get("reminders", "reminder-0").orElseThrow();
        assertEquals("call the supplier", reminder.value());
        assertEquals("9am", reminder.metadata().get("when"));
        assertEquals(1, second.query("session").size());
    }

    @Test
    void deleteAndClearPersistAcrossRestarts() {
        FileBackedStore first = new FileBackedStore(storeFile());
        first.put("a", "k1", "v1");
        first.put("a", "k2", "v2");
        first.put("b", "k1", "v1");
        assertTrue(first.delete("a", "k1"));
        first.clear("b");

        FileBackedStore second = new FileBackedStore(storeFile());
        assertTrue(second.get("a", "k1").isEmpty());
        assertEquals("v2", second.get("a", "k2").orElseThrow().value());
        assertTrue(second.query("b").isEmpty());
    }

    @Test
    void awkwardCharactersRoundTripSafely() {
        String nasty = "line1\nline2\ttabbed & encoded = tricky ✓";
        FileBackedStore first = new FileBackedStore(storeFile());
        first.put("s", "k", nasty, Map.of("note=key", "a&b\tc"));

        FileBackedStore second = new FileBackedStore(storeFile());
        MemoryEntry<String> entry = second.get("s", "k").orElseThrow();
        assertEquals(nasty, entry.value());
        assertEquals("a&b\tc", entry.metadata().get("note=key"));
    }

    @Test
    void overwriteKeepsOneEntryPerKey() {
        FileBackedStore store = new FileBackedStore(storeFile());
        store.put("s", "k", "first");
        store.put("s", "k", "second");

        FileBackedStore reloaded = new FileBackedStore(storeFile());
        assertEquals(1, reloaded.query("s").size());
        assertEquals("second", reloaded.get("s", "k").orElseThrow().value());
    }

    @Test
    void behavesLikeAMemoryStoreContract() {
        MemoryStore<String> store = new FileBackedStore(storeFile());
        store.put("scope-a", "shared", "a");
        store.put("scope-b", "shared", "b");
        assertEquals("a", store.get("scope-a", "shared").orElseThrow().value());
        assertEquals("b", store.get("scope-b", "shared").orElseThrow().value());
        assertFalse(store.delete("scope-a", "absent"));
        store.clear();
        assertTrue(store.query("scope-a").isEmpty());
    }

    @Test
    void missingFileMeansEmptyStoreNotError() {
        FileBackedStore store = new FileBackedStore(dir.resolve("never-created.tsv"));
        assertTrue(store.query("anything").isEmpty());
    }
}
