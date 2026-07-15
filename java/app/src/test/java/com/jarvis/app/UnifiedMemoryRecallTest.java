package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.memory.InMemoryStore;
import com.jarvis.memory.MemoryStore;
import com.jarvis.rag.EmbeddingProvider;
import org.junit.jupiter.api.Test;

/**
 * Guards issue #2: chat recall draws user facts from the single unified store (the semantic
 * memory service), so a fact added in the Personal Intelligence tab reaches conversations — the
 * same store the Memory tab reads and writes.
 */
class UnifiedMemoryRecallTest {

    @Test
    void recallDrawsFactsFromTheUnifiedSemanticStore() {
        SemanticMemoryService semantic = new SemanticMemoryService(
                new InMemoryRecordStore(), EmbeddingProvider.DORMANT, null);
        semantic.remember("Coffee", "black, no sugar", 1L);
        semantic.remember("", "prefers metric units", 2L);
        MemoryStore<String> memory = new InMemoryStore<>();
        memory.put("about", "me", "I'm Bill.");

        String block = AppWiring.recall(memory, semantic);
        assertTrue(block.contains("Coffee: black, no sugar"), block);
        assertTrue(block.contains("prefers metric units"), block);
        assertTrue(block.contains("About the user: I'm Bill."), block);
    }

    @Test
    void forgottenFactsLeaveTheRecallBlock() {
        SemanticMemoryService semantic = new SemanticMemoryService(
                new InMemoryRecordStore(), EmbeddingProvider.DORMANT, null);
        String id = semantic.remember("Secret", "delete me", 1L);
        MemoryStore<String> memory = new InMemoryStore<>();
        assertTrue(AppWiring.recall(memory, semantic).contains("delete me"));

        assertTrue(semantic.forget(id));
        assertFalse(AppWiring.recall(memory, semantic).contains("delete me"));
    }

    @Test
    void withoutSemanticRecallFallsBackToPreferences() {
        // Minimal harness (no semantic wired) still recalls key/value preferences.
        MemoryStore<String> memory = new InMemoryStore<>();
        memory.put("preferences", "pref-1", "likes espresso");
        String block = AppWiring.recall(memory);
        assertTrue(block.contains("likes espresso"), block);
    }
}
