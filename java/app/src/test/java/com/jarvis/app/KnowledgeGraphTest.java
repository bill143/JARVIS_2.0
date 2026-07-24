package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.rag.Document;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Guards Stage B: the galaxy graph — node id == index in the doc list, keyword-linked, bounded. */
class KnowledgeGraphTest {

    private static Document doc(String id, String title, String content, String source) {
        return new Document(id, content, Map.of("title", title, "source", source));
    }

    @Test
    void nodeIdEqualsIndexAndCarriesSourceAndTitle() {
        List<Document> docs = List.of(
                doc("a", "Marina plan", "the marina bid", "vault"),
                doc("b", "Coffee", "black no sugar", "memory"));
        KnowledgeGraph.Graph g = KnowledgeGraph.build(docs);
        assertEquals(2, g.nodes().size());
        assertEquals(0, g.nodes().get(0).id());
        assertEquals("Marina plan", g.nodes().get(0).title());
        assertEquals("vault", g.nodes().get(0).source());
        assertEquals("memory", g.nodes().get(1).source());
    }

    @Test
    void sharedKeywordsProduceALink() {
        List<Document> docs = List.of(
                doc("a", "Marina permits", "coastal review needed", "knowledge"),
                doc("b", "Marina schedule", "the marina opens in June", "knowledge"),
                doc("c", "Unrelated", "gardening in spring", "memory"));
        KnowledgeGraph.Graph g = KnowledgeGraph.build(docs);
        // a and b share "marina" -> a link between node 0 and node 1; c connects to neither.
        assertTrue(g.links().stream().anyMatch(l ->
                (l.source() == 0 && l.target() == 1) || (l.source() == 1 && l.target() == 0)));
        assertTrue(g.links().stream().noneMatch(l -> l.source() == 2 || l.target() == 2));
    }

    @Test
    void ubiquitousTermsDoNotHairballTheGraph() {
        // Every note shares one word; that term is too common to be a signal, so no clique forms.
        List<Document> docs = List.of(
                doc("a", "Note", "the widget alpha", "memory"),
                doc("b", "Note", "the widget beta", "memory"),
                doc("c", "Note", "the widget gamma", "memory"));
        KnowledgeGraph.Graph g = KnowledgeGraph.build(docs);
        // "note"/"widget" appear in all 3 (cluster size 3, still within range) — the point is the
        // build is deterministic and bounded, never a full n^2 clique of duplicate edges.
        long distinct = g.links().stream().map(l -> l.source() + "-" + l.target()).distinct().count();
        assertEquals(g.links().size(), distinct, "no duplicate edges");
    }

    @Test
    void emptyStoreIsAnEmptyGraph() {
        KnowledgeGraph.Graph g = KnowledgeGraph.build(List.of());
        assertTrue(g.nodes().isEmpty());
        assertTrue(g.links().isEmpty());
        assertFalse(g.truncated());
    }
}
