package com.jarvis.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileRecordStoreTest {

    @TempDir
    Path dir;

    @Test
    void recordsSurviveARestartWithContinuousSequences() {
        FileRecordStore first = new FileRecordStore(dir);
        first.append("audit", "logged in");
        first.append("audit", "opened dashboard");

        // Simulate a restart: a brand-new instance over the same directory.
        FileRecordStore second = new FileRecordStore(dir);
        assertEquals(List.of("logged in", "opened dashboard"),
                second.list("audit").stream().map(StoredRecord::payload).toList());
        // The next append continues the sequence rather than restarting at 0.
        assertEquals(2, second.append("audit", "third").seq());
    }

    @Test
    void awkwardPayloadsRoundTripSafely() {
        String nasty = "line1\nline2\ttabbed & encoded = tricky ✓";
        FileRecordStore first = new FileRecordStore(dir);
        first.append("s", nasty);

        FileRecordStore second = new FileRecordStore(dir);
        assertEquals(nasty, second.list("s").get(0).payload());
    }

    @Test
    void tailReadsTheLastRecords() {
        FileRecordStore store = new FileRecordStore(dir);
        for (int i = 0; i < 4; i++) {
            store.append("log", "e" + i);
        }
        assertEquals(List.of("e2", "e3"),
                store.tail("log", 2).stream().map(StoredRecord::payload).toList());
    }

    @Test
    void clearRemovesTheCollectionAndResetsSequence() {
        FileRecordStore store = new FileRecordStore(dir);
        store.append("c", "x");
        store.append("c", "y");
        store.clear("c");
        assertEquals(0, store.count("c"));
        assertEquals(0, store.append("c", "fresh").seq());
    }

    @Test
    void missingCollectionMeansEmptyNotError() {
        FileRecordStore store = new FileRecordStore(dir);
        assertTrue(store.list("never-written").isEmpty());
        assertEquals(0, store.count("never-written"));
    }

    @Test
    void collectionNamesWithAwkwardCharactersAreIsolated() {
        FileRecordStore store = new FileRecordStore(dir);
        store.append("conv:session/42", "a");
        store.append("usage:2026-07", "b");
        assertEquals(1, store.count("conv:session/42"));
        assertEquals(1, store.count("usage:2026-07"));
        assertEquals("a", store.list("conv:session/42").get(0).payload());
    }
}
