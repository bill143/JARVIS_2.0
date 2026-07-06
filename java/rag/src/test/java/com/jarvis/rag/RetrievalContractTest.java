package com.jarvis.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class RetrievalContractTest {

    /**
     * Test fake proving the adapter contracts are implementable together: naive term-overlap
     * scoring over an in-memory map. Not a production retriever.
     */
    private static final class KeywordFake implements Retriever, DocumentIndexer {
        private final Map<String, Document> byId = new ConcurrentHashMap<>();

        @Override
        public void index(Document document) {
            byId.put(document.id(), document);
        }

        @Override
        public boolean remove(String id) {
            return byId.remove(id) != null;
        }

        @Override
        public List<ScoredDocument> retrieve(RetrievalQuery query) {
            String[] terms = query.text().toLowerCase(Locale.ROOT).split("\\s+");
            return byId.values().stream()
                    .map(doc -> new ScoredDocument(doc, overlap(doc, terms)))
                    .filter(scored -> scored.score() > 0)
                    .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
                    .limit(query.topK())
                    .toList();
        }

        private static double overlap(Document doc, String[] terms) {
            String content = doc.content().toLowerCase(Locale.ROOT);
            double hits = 0;
            for (String term : terms) {
                if (content.contains(term)) {
                    hits++;
                }
            }
            return hits;
        }
    }

    private static KeywordFake indexed() {
        KeywordFake fake = new KeywordFake();
        fake.index(Document.of("tea", "how to brew green tea with boiling water"));
        fake.index(Document.of("coffee", "how to brew espresso coffee"));
        fake.index(Document.of("bread", "baking sourdough bread at home"));
        return fake;
    }

    @Test
    void retrievesRelevantDocumentsInDescendingScoreOrder() {
        List<ScoredDocument> results = indexed().retrieve(new RetrievalQuery("brew tea water", 10));

        assertEquals(List.of("tea", "coffee"),
                results.stream().map(r -> r.document().id()).toList());
        assertTrue(results.get(0).score() >= results.get(1).score());
    }

    @Test
    void topKBoundsTheResultCount() {
        List<ScoredDocument> results = indexed().retrieve(new RetrievalQuery("how to brew", 1));
        assertEquals(1, results.size());
    }

    @Test
    void noRelevanceMeansEmptyNotError() {
        assertTrue(indexed().retrieve(new RetrievalQuery("quantum physics", 5)).isEmpty());
    }

    @Test
    void reindexingSameIdReplacesTheDocument() {
        KeywordFake fake = indexed();
        fake.index(Document.of("tea", "completely unrelated text about physics"));

        List<ScoredDocument> results = fake.retrieve(new RetrievalQuery("green tea", 10));
        assertFalse(results.stream().anyMatch(r -> r.document().id().equals("tea")
                && r.document().content().contains("green")));
    }

    @Test
    void removeReportsPriorPresence() {
        KeywordFake fake = indexed();
        assertTrue(fake.remove("bread"));
        assertFalse(fake.remove("bread"));
        assertTrue(fake.retrieve(new RetrievalQuery("sourdough bread", 5)).isEmpty());
    }

    @Test
    void queryValidatesItsArguments() {
        assertThrows(NullPointerException.class, () -> new RetrievalQuery(null, 3));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalQuery("x", 0));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalQuery("x", -1));
    }

    @Test
    void scoredDocumentRejectsNaNAndNullDocument() {
        assertThrows(NullPointerException.class, () -> new ScoredDocument(null, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new ScoredDocument(Document.of("d", "c"), Double.NaN));
    }

    @Test
    void documentMetadataIsDefensivelyCopiedAndImmutable() {
        Map<String, String> mutable = new java.util.HashMap<>(Map.of("source", "unit-test"));
        Document doc = new Document("d", "content", mutable);
        mutable.put("source", "changed");

        assertEquals("unit-test", doc.metadata().get("source"));
        assertThrows(UnsupportedOperationException.class, () -> doc.metadata().put("k", "v"));
    }
}
