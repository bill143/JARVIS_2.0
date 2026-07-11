package com.jarvis.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class KeywordIndexTest {

    private KeywordIndex indexed() {
        KeywordIndex idx = new KeywordIndex();
        idx.index(Document.of("d1", "The go-kart project uses a brushless motor and lithium battery."));
        idx.index(Document.of("d2", "Grocery list: milk, eggs, bread."));
        idx.index(Document.of("d3", "Meeting notes about the quarterly budget and hiring plan."));
        return idx;
    }

    @Test
    void retrievesByKeywordOverlapRankedByScore() {
        List<ScoredDocument> hits = indexed().retrieve(new RetrievalQuery("brushless motor battery", 5));
        assertEquals("d1", hits.get(0).document().id());
        assertTrue(hits.get(0).score() > 0);
    }

    @Test
    void respectsTopK() {
        KeywordIndex idx = indexed();
        idx.index(Document.of("d4", "another budget note about hiring"));
        assertEquals(1, idx.retrieve(new RetrievalQuery("budget hiring", 1)).size());
    }

    @Test
    void unrelatedQueryReturnsNothing() {
        assertTrue(indexed().retrieve(new RetrievalQuery("submarine periscope", 5)).isEmpty());
    }

    @Test
    void removeDropsADocument() {
        KeywordIndex idx = indexed();
        assertTrue(idx.remove("d1"));
        assertTrue(idx.retrieve(new RetrievalQuery("brushless motor", 5)).isEmpty());
        assertEquals(2, idx.all().size());
    }
}
