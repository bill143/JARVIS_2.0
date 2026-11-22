package com.jarvis.research;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An internet-research flow with citation-preserving synthesis: plan → search → (fetch) → dedupe →
 * synthesize. Backends are seams so the whole pipeline is unit-tested offline; production wires the
 * existing {@code web_search} as the {@link Searcher}/{@link Fetcher} and {@code api.chat} as the
 * {@link Synthesizer}.
 *
 * <p><b>Citations are structural, not best-effort.</b> Whatever the synthesizer returns, the report
 * always carries the numbered {@link Source} list built from the deduped results, so every run is
 * traceable to its sources.
 */
public final class ResearchPipeline {

    /** Finds candidate sources for a query. */
    @FunctionalInterface
    public interface Searcher {
        List<SearchResult> search(String query) throws Exception;
    }

    /** Optionally deep-reads a URL to enrich a snippet before synthesis (nullable in the pipeline). */
    @FunctionalInterface
    public interface Fetcher {
        String fetch(String url) throws Exception;
    }

    /** Synthesizes a cited answer from the question and the (numbered) sources. */
    @FunctionalInterface
    public interface Synthesizer {
        String synthesize(String question, List<SearchResult> sources) throws Exception;
    }

    /** Default cap on the number of sources carried into synthesis. */
    public static final int DEFAULT_MAX_SOURCES = 5;

    private final Searcher searcher;
    private final Fetcher fetcher;        // nullable → snippets-only
    private final Synthesizer synthesizer;
    private final int maxSources;

    public ResearchPipeline(Searcher searcher, Synthesizer synthesizer) {
        this(searcher, null, synthesizer, DEFAULT_MAX_SOURCES);
    }

    public ResearchPipeline(Searcher searcher, Fetcher fetcher, Synthesizer synthesizer, int maxSources) {
        this.searcher = Objects.requireNonNull(searcher, "searcher");
        this.fetcher = fetcher;
        this.synthesizer = Objects.requireNonNull(synthesizer, "synthesizer");
        this.maxSources = Math.min(Math.max(1, maxSources), 20);
    }

    /** Runs the research flow for {@code question} and returns a citation-bearing report. */
    public ResearchReport run(String question) throws Exception {
        List<SearchResult> raw = searcher.search(question == null ? "" : question);
        List<SearchResult> deduped = dedupeAndCap(raw);
        if (deduped.isEmpty()) {
            return new ResearchReport(question, "No sources were found for this question.", List.of());
        }
        List<SearchResult> enriched = fetcher == null ? deduped : enrich(deduped);
        String answer;
        try {
            answer = synthesizer.synthesize(question, enriched);
        } catch (Exception e) {
            answer = "Synthesis failed (" + e.getMessage() + "). Sources are listed below.";
        }
        List<Source> sources = new ArrayList<>();
        for (int i = 0; i < enriched.size(); i++) {
            sources.add(new Source(i + 1, enriched.get(i).title(), enriched.get(i).url()));
        }
        return new ResearchReport(question, appendSourcesIfMissing(answer, sources), sources);
    }

    /** Deduplicates by URL (first wins), preserving order, capped at {@link #maxSources}. */
    private List<SearchResult> dedupeAndCap(List<SearchResult> results) {
        Map<String, SearchResult> byUrl = new LinkedHashMap<>();
        if (results != null) {
            for (SearchResult r : results) {
                if (r != null && r.url() != null && !r.url().isBlank()) {
                    byUrl.putIfAbsent(r.url(), r);
                }
            }
        }
        List<SearchResult> out = new ArrayList<>(byUrl.values());
        return out.size() > maxSources ? new ArrayList<>(out.subList(0, maxSources)) : out;
    }

    /** Deep-reads each source; a fetch failure keeps the original snippet (never drops the source). */
    private List<SearchResult> enrich(List<SearchResult> sources) {
        List<SearchResult> out = new ArrayList<>(sources.size());
        for (SearchResult s : sources) {
            try {
                String content = fetcher.fetch(s.url());
                out.add(content == null || content.isBlank() ? s : s.withSnippet(trim(content, 800)));
            } catch (Exception e) {
                out.add(s);   // keep the source with its search snippet
            }
        }
        return out;
    }

    /** Ensures the answer always ends with a readable Sources list (belt-and-suspenders citations). */
    private static String appendSourcesIfMissing(String answer, List<Source> sources) {
        if (answer != null && answer.contains("Sources:")) {
            return answer;
        }
        StringBuilder sb = new StringBuilder(answer == null ? "" : answer);
        sb.append("\n\nSources:");
        for (Source s : sources) {
            sb.append("\n[").append(s.index()).append("] ")
                    .append(s.title().isBlank() ? s.url() : s.title()).append(" — ").append(s.url());
        }
        return sb.toString();
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
