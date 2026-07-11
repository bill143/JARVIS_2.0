package com.jarvis.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticMemoryTest {

    /** A tiny deterministic embedder: a fixed vocabulary → term-frequency vector. Offline. */
    private static EmbeddingProvider vocab(String... words) {
        return new EmbeddingProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public float[] embed(String text) {
                String lower = text.toLowerCase(java.util.Locale.ROOT);
                float[] v = new float[words.length];
                for (int i = 0; i < words.length; i++) {
                    int from = 0;
                    while ((from = lower.indexOf(words[i], from)) >= 0) {
                        v[i]++;
                        from += words[i].length();
                    }
                }
                return v;
            }
        };
    }

    @Test
    void cosineRanksTheClosestVectorFirst() {
        VectorIndex idx = new VectorIndex();
        idx.put("a", new float[] {1, 0, 0});
        idx.put("b", new float[] {0, 1, 0});
        idx.put("c", new float[] {1, 1, 0});
        List<VectorIndex.Scored> hits = idx.search(new float[] {1, 0, 0}, 3);
        assertEquals("a", hits.get(0).id());          // exact direction wins
        assertTrue(hits.get(0).score() > hits.get(1).score());
        assertFalse(hits.stream().anyMatch(h -> h.id().equals("b")));   // orthogonal dropped
    }

    @Test
    void dormantProviderNeverEmbedsAndFallsBackToKeyword() {
        SemanticMemory mem = new SemanticMemory(EmbeddingProvider.DORMANT);
        assertFalse(mem.semantic());
        assertEquals("keyword", mem.mode());
        mem.add(new Document("1", "brushless motor and battery pack", Map.of("title", "Go-kart")));
        List<ScoredDocument> hits = mem.search("battery brushless", 5);
        assertEquals("1", hits.get(0).document().id());   // keyword overlap still recalls it
    }

    @Test
    void semanticRecallFindsMeaningWhenEmbeddingsAreLive() {
        SemanticMemory mem = new SemanticMemory(vocab("battery", "brake", "motor"));
        assertTrue(mem.semantic());
        assertEquals("semantic", mem.mode());
        mem.add(Document.of("energy", "the battery and motor drive the wheels"));
        mem.add(Document.of("stop", "the brake slows the wheels"));
        List<ScoredDocument> hits = mem.search("how strong is the brake", 1);
        assertEquals("stop", hits.get(0).document().id());
    }

    @Test
    void removeDropsFromBothIndexes() {
        SemanticMemory mem = new SemanticMemory(vocab("cat", "dog"));
        mem.add(Document.of("x", "cat cat"));
        assertTrue(mem.remove("x"));
        assertFalse(mem.remove("x"));
        assertTrue(mem.search("cat", 3).isEmpty());
        assertTrue(mem.all().isEmpty());
    }

    @Test
    void anEmbeddingFailureLeavesTheDocumentRecallableByKeyword() {
        EmbeddingProvider flaky = new EmbeddingProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public float[] embed(String text) {
                throw new RuntimeException("embedding backend down");
            }
        };
        SemanticMemory mem = new SemanticMemory(flaky);
        mem.add(new Document("1", "aluminium chassis welding", Map.of("title", "Frame")));
        // search embeds the query, fails, and falls back to keyword — the doc is still found.
        assertEquals("1", mem.search("welding chassis", 5).get(0).document().id());
    }
}
