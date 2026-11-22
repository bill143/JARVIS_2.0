package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.rag.Document;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Guards Stage A requirement #1: per-question retrieval with title weighting, top-K only. */
class KnowledgeGroundingTest {

    private static Document doc(String id, String title, String content) {
        return new Document(id, content, Map.of("title", title));
    }

    private static final List<Document> NODES = List.of(
            doc("n0", "Riverfront project", "The riverfront bid is due Friday for the marina job."),
            doc("n1", "Coffee", "black, no sugar"),
            doc("n2", "Marina permits", "Permitting for the marina requires a coastal review."),
            doc("n3", "Unrelated", "notes about the weather and the garden"));

    @Test
    void retrievesOnlyRelevantNotesRankedByOverlap() {
        List<KnowledgeGrounding.Scored> hits =
                KnowledgeGrounding.retrieve(NODES, "when is the marina bid due", 6);
        assertFalse(hits.isEmpty());
        // The riverfront note mentions marina + bid + due — it should top the marina-permits note.
        assertEquals(0, hits.get(0).id());
        // The unrelated note shares no query terms and must be excluded entirely.
        assertTrue(hits.stream().noneMatch(h -> h.id() == 3), "unrelated note leaked in");
    }

    @Test
    void titleMatchesOutrankBodyMatches() {
        // "coffee" appears in n1's title only; give it a competitor whose body mentions coffee.
        List<Document> nodes = List.of(
                doc("t", "Coffee", "black, no sugar"),
                doc("b", "Morning routine", "I drink coffee then read"));
        List<KnowledgeGrounding.Scored> hits = KnowledgeGrounding.retrieve(nodes, "coffee", 6);
        assertEquals(2, hits.size());
        assertEquals(0, hits.get(0).id(), "title match should rank first");
        assertTrue(hits.get(0).score() > hits.get(1).score(), "title weight should beat body weight");
    }

    @Test
    void capsAtTopK() {
        List<Document> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(doc("d" + i, "budget note " + i, "the annual budget figure is item " + i));
        }
        List<KnowledgeGrounding.Scored> hits = KnowledgeGrounding.retrieve(many, "budget", 6);
        assertEquals(6, hits.size(), "must return at most top-K");
    }

    @Test
    void idIsTheIndexIntoTheNodeList() {
        List<KnowledgeGrounding.Scored> hits = KnowledgeGrounding.retrieve(NODES, "permits", 6);
        assertFalse(hits.isEmpty());
        // The permits note is at index 2 in NODES; its cited id must be that index.
        assertEquals(2, hits.get(0).id());
    }

    @Test
    void emptyAndStopwordOnlyQueriesGroundNothing() {
        assertTrue(KnowledgeGrounding.retrieve(NODES, "", 6).isEmpty());
        assertTrue(KnowledgeGrounding.retrieve(NODES, "   ", 6).isEmpty());
        assertTrue(KnowledgeGrounding.retrieve(NODES, "the a of to", 6).isEmpty(), "all-stopword query");
        assertTrue(KnowledgeGrounding.retrieve(List.of(), "marina", 6).isEmpty());
    }

    @Test
    void contextBlockListsRetrievedNotesAndIsEmptyWhenNoHits() {
        assertEquals("", KnowledgeGrounding.contextBlock(List.of()));
        List<KnowledgeGrounding.Scored> hits = KnowledgeGrounding.retrieve(NODES, "marina bid", 6);
        String block = KnowledgeGrounding.contextBlock(hits);
        assertTrue(block.contains("Relevant notes"), block);
        assertTrue(block.contains("Riverfront project"), block);
    }
}
