package com.jarvis.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResearchPipelineTest {

    private static SearchResult r(String title, String url) {
        return new SearchResult(title, url, "snippet for " + title);
    }

    @Test
    void reportCarriesNumberedSourcesEvenIfTheModelOmitsThem() throws Exception {
        ResearchPipeline p = new ResearchPipeline(
                q -> List.of(r("A", "https://a.example"), r("B", "https://b.example")),
                (question, sources) -> "The answer, no inline markers.");   // model omits citations
        ResearchReport report = p.run("what is X?");
        assertTrue(report.hasCitations());
        assertEquals(2, report.sources().size());
        assertEquals(1, report.sources().get(0).index());
        assertEquals("https://a.example", report.sources().get(0).url());
        // Citations are appended structurally.
        assertTrue(report.answer().contains("Sources:"));
        assertTrue(report.answer().contains("https://a.example"));
    }

    @Test
    void dedupesAndCapsSources() throws Exception {
        ResearchPipeline p = new ResearchPipeline(
                q -> List.of(r("A", "https://dup.example"), r("A2", "https://dup.example"),
                        r("B", "https://b.example"), r("C", "https://c.example"),
                        r("D", "https://d.example"), r("E", "https://e.example"),
                        r("F", "https://f.example")),
                (question, sources) -> "answer");
        // pipeline with maxSources=3
        ResearchPipeline capped = new ResearchPipeline(
                q -> List.of(r("A", "https://dup.example"), r("A2", "https://dup.example"),
                        r("B", "https://b.example"), r("C", "https://c.example"),
                        r("D", "https://d.example")),
                null, (question, sources) -> "answer", 3);
        ResearchReport report = capped.run("q");
        assertEquals(3, report.sources().size());                     // capped
        assertEquals("https://dup.example", report.sources().get(0).url());
        assertEquals("https://b.example", report.sources().get(1).url());   // dup collapsed
    }

    @Test
    void synthesizerSeesTheDedupedSources() throws Exception {
        int[] seen = {0};
        ResearchPipeline p = new ResearchPipeline(
                q -> List.of(r("A", "https://a.example"), r("A", "https://a.example")),
                (question, sources) -> { seen[0] = sources.size(); return "ok"; });
        p.run("q");
        assertEquals(1, seen[0]);   // deduped before synthesis
    }

    @Test
    void emptySearchIsGraceful() throws Exception {
        ResearchPipeline p = new ResearchPipeline(q -> List.of(),
                (question, sources) -> "should not be called");
        ResearchReport report = p.run("q");
        assertFalse(report.hasCitations());
        assertTrue(report.answer().contains("No sources"));
    }

    @Test
    void fetcherEnrichesSnippetsButFailuresKeepTheSource() throws Exception {
        ResearchPipeline p = new ResearchPipeline(
                q -> List.of(r("A", "https://a.example"), r("B", "https://b.example")),
                url -> url.contains("a.example") ? "fetched body for A" : bomb(),
                (question, sources) -> sources.get(0).snippet(), 5);
        ResearchReport report = p.run("q");
        assertEquals(2, report.sources().size());   // failed fetch did not drop B
        assertTrue(report.answer().contains("fetched body for A"));
    }

    private static String bomb() {
        throw new RuntimeException("fetch failed");
    }
}
