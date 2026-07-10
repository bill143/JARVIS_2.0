package com.jarvis.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryRecordStoreTest {

    @Test
    void appendAssignsContiguousSequencesAndPreservesOrder() {
        RecordStore store = new InMemoryRecordStore();
        assertEquals(0, store.append("audit", "one").seq());
        assertEquals(1, store.append("audit", "two").seq());
        assertEquals(2, store.append("audit", "three").seq());

        List<StoredRecord> records = store.list("audit");
        assertEquals(List.of("one", "two", "three"),
                records.stream().map(StoredRecord::payload).toList());
        assertEquals(3, store.count("audit"));
    }

    @Test
    void collectionsAreIsolated() {
        RecordStore store = new InMemoryRecordStore();
        store.append("a", "a0");
        store.append("b", "b0");
        assertEquals(0, store.append("a", "a1").seq() - 1); // a has its own sequence
        assertEquals(0, store.append("b", "b1").seq() - 1);
        assertEquals(2, store.count("a"));
        assertEquals(2, store.count("b"));
        assertEquals("a0", store.list("a").get(0).payload());
    }

    @Test
    void tailReturnsTheLastRecordsInOrder() {
        RecordStore store = new InMemoryRecordStore();
        for (int i = 0; i < 5; i++) {
            store.append("log", "e" + i);
        }
        assertEquals(List.of("e3", "e4"),
                store.tail("log", 2).stream().map(StoredRecord::payload).toList());
        assertEquals(5, store.tail("log", 99).size());   // fewer than max -> all
        assertTrue(store.tail("log", 0).isEmpty());
    }

    @Test
    void unknownCollectionIsEmptyNotAnError() {
        RecordStore store = new InMemoryRecordStore();
        assertTrue(store.list("nope").isEmpty());
        assertEquals(0, store.count("nope"));
        assertTrue(store.tail("nope", 5).isEmpty());
    }

    @Test
    void clearEmptiesACollection() {
        RecordStore store = new InMemoryRecordStore();
        store.append("c", "x");
        store.clear("c");
        assertEquals(0, store.count("c"));
    }

    @Test
    void argumentValidation() {
        RecordStore store = new InMemoryRecordStore();
        assertThrows(NullPointerException.class, () -> store.append(null, "v"));
        assertThrows(NullPointerException.class, () -> store.append("c", null));
        assertThrows(IllegalArgumentException.class, () -> store.tail("c", -1));
        assertThrows(IllegalArgumentException.class, () -> new StoredRecord(-1, java.time.Instant.now(), "p"));
    }
}
