package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.memory.InMemoryStore;
import com.jarvis.memory.MemoryStore;
import com.jarvis.rag.EmbeddingProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards issue #2 under the Stage A architecture: user facts still reach conversations from the
 * single unified store — but now via <b>per-question retrieval</b> ({@link KnowledgeGrounding}) at
 * chat time rather than a static whole-store dump in the recall block. The recall block itself now
 * carries only durable identity context (directions, about-me), never the fact dump.
 */
class UnifiedMemoryRecallTest {

    private static SemanticMemoryService store() {
        return new SemanticMemoryService(new InMemoryRecordStore(), EmbeddingProvider.DORMANT, null);
    }

    @Test
    void recallBlockNoLongerDumpsTheSemanticStore() {
        SemanticMemoryService semantic = store();
        semantic.remember("Coffee", "black, no sugar", 1L);
        semantic.remember("", "prefers metric units", 2L);
        MemoryStore<String> memory = new InMemoryStore<>();
        memory.put("about", "me", "I'm Bill.");

        String block = AppWiring.recall(memory, semantic);
        // Identity context rides along on every turn...
        assertTrue(block.contains("About the user: I'm Bill."), block);
        // ...but the individual facts are NOT dumped anymore (they're retrieved per question instead).
        assertFalse(block.contains("black, no sugar"), block);
        assertFalse(block.contains("prefers metric units"), block);
    }

    @Test
    void factsReachChatViaPerQuestionRetrieval() {
        SemanticMemoryService semantic = store();
        semantic.remember("Coffee", "black, no sugar", 1L);
        semantic.remember("Units", "prefers metric units", 2L);

        // Asking about coffee surfaces the coffee fact from the same unified store.
        List<KnowledgeGrounding.Scored> hits =
                KnowledgeGrounding.retrieve(semantic.all(), "how do you take your coffee", 6);
        assertTrue(hits.stream().anyMatch(h -> h.content().contains("black, no sugar")), hits.toString());
    }

    @Test
    void forgottenFactsLeaveRetrieval() {
        SemanticMemoryService semantic = store();
        String id = semantic.remember("Secret", "the passphrase is orchid", 1L);
        assertTrue(KnowledgeGrounding.retrieve(semantic.all(), "passphrase", 6).stream()
                .anyMatch(h -> h.content().contains("orchid")));

        assertTrue(semantic.forget(id));
        assertFalse(KnowledgeGrounding.retrieve(semantic.all(), "passphrase", 6).stream()
                .anyMatch(h -> h.content().contains("orchid")));
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
