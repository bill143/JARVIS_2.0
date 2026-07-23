package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.rag.Document;
import com.jarvis.rag.EmbeddingProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Guards Stage A req #4 plumbing: one store, provenance-tagged, idempotent fold-in of knowledge. */
class SemanticMemoryServiceTest {

    private static SemanticMemoryService over(InMemoryRecordStore store) {
        return new SemanticMemoryService(store, EmbeddingProvider.DORMANT, null);
    }

    @Test
    void ingestIsIdempotentByStableId() {
        SemanticMemoryService s = over(new InMemoryRecordStore());
        s.ingest("knowledge-a", "Alpha", "first", "knowledge");
        s.ingest("knowledge-a", "Alpha", "second", "knowledge");   // same id → replace, not duplicate
        List<Document> all = s.all();
        assertEquals(1, all.stream().filter(d -> d.id().equals("knowledge-a")).count());
        assertEquals("second",
                all.stream().filter(d -> d.id().equals("knowledge-a")).findFirst().get().content());
    }

    @Test
    void provenanceSurvivesReplay() {
        InMemoryRecordStore store = new InMemoryRecordStore();
        SemanticMemoryService s = over(store);
        s.rememberSource("Bid", "riverfront due Friday", "knowledge", 1L);
        s.syncVault(List.of(new Document("notes/plan.md", "the plan", Map.of("title", "Plan"))));

        // A fresh service replaying the same log must see the same source tags.
        SemanticMemoryService replayed = over(store);
        String knowledgeSrc = replayed.all().stream()
                .filter(d -> d.content().contains("riverfront")).findFirst()
                .map(SemanticMemoryService::sourceOf).orElse("");
        String vaultSrc = replayed.all().stream()
                .filter(d -> d.id().startsWith("vault-")).findFirst()
                .map(SemanticMemoryService::sourceOf).orElse("");
        assertEquals("knowledge", knowledgeSrc);
        assertEquals("vault", vaultSrc);
    }

    @Test
    void plainRememberIsTaggedMemory() {
        SemanticMemoryService s = over(new InMemoryRecordStore());
        s.remember("Coffee", "black", 1L);
        assertTrue(s.all().stream().allMatch(d -> "memory".equals(SemanticMemoryService.sourceOf(d))));
    }
}
