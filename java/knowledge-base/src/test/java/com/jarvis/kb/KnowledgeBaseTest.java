package com.jarvis.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.memory.FileRecordStore;
import com.jarvis.memory.InMemoryRecordStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeBaseTest {

    @Test
    void addSearchAndDelete() {
        KnowledgeBase kb = new KnowledgeBase(new InMemoryRecordStore());
        kb.add("Go-kart", "brushless motor and lithium battery wiring", 1);
        kb.add("Groceries", "milk eggs bread", 2);

        var hits = kb.search("brushless battery", 3);
        assertEquals(1, hits.size());
        assertEquals("Go-kart", KnowledgeBase.titleOf(hits.get(0).document()));
        assertEquals(2, kb.list().size());
    }

    @Test
    void documentsAndIndexSurviveARestart(@TempDir Path dir) {
        String id = new KnowledgeBase(new FileRecordStore(dir))
                .add("Note", "the quarterly budget planning document", 1);

        KnowledgeBase reopened = new KnowledgeBase(new FileRecordStore(dir));
        assertEquals(1, reopened.list().size());
        assertTrue(reopened.search("budget planning", 5).size() >= 1);   // index rebuilt on load

        reopened.delete(id);
        assertTrue(reopened.list().isEmpty());
        assertTrue(new KnowledgeBase(new FileRecordStore(dir)).list().isEmpty());   // delete persisted
    }
}
