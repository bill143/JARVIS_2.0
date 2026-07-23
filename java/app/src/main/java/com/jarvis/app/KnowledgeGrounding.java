package com.jarvis.app;

import com.jarvis.rag.Document;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Per-question knowledge retrieval for the chat brain. Instead of dumping the whole store into the
 * system prompt (which drowns the model and never targets the actual question), this scores every
 * knowledge node against the user's question by keyword overlap — with title matches weighted higher
 * than body matches — and returns only the top handful.
 *
 * <p>The result feeds two things at once: a compact grounding block prepended to the prompt, and the
 * list of cited sources returned to the UI. Each source carries its node {@code id}, which is the
 * node's index in the canonical node list ({@code semantic.all()}), so the Knowledge galaxy can fly
 * straight to it.
 */
final class KnowledgeGrounding {

    /** How many notes to ground on per question. */
    static final int DEFAULT_TOP_K = 6;

    private static final int TITLE_WEIGHT = 2;
    private static final int CONTENT_WEIGHT = 1;
    private static final int MAX_NOTE_CHARS = 600;

    /** A tiny stopword set so ubiquitous words don't score every note. */
    private static final Set<String> STOP = Set.of(
            "the", "a", "an", "and", "or", "of", "to", "in", "is", "are", "was", "were", "be", "for",
            "on", "with", "that", "this", "it", "as", "at", "by", "my", "your", "i", "you", "me",
            "do", "does", "how", "what", "who", "when", "where", "why", "can", "could", "should",
            "would", "please", "tell", "about", "am", "we", "our");

    private KnowledgeGrounding() {
    }

    /** One scored, citeable node. {@code id} is its index in the source node list. */
    record Scored(int id, String title, String content, double score) {
    }

    /**
     * Scores {@code nodes} against {@code query} and returns the top {@code topK} by keyword overlap
     * (title terms weighted {@value #TITLE_WEIGHT}×, body terms {@value #CONTENT_WEIGHT}×). Only nodes
     * with a positive score are returned; ties keep the original (stable) order. {@code id} is the
     * node's index in {@code nodes}, so it stays aligned with the galaxy node list.
     */
    static List<Scored> retrieve(List<Document> nodes, String query, int topK) {
        List<Scored> out = new ArrayList<>();
        if (nodes == null || nodes.isEmpty() || query == null || query.isBlank() || topK <= 0) {
            return out;
        }
        List<String> terms = terms(query);
        if (terms.isEmpty()) {
            return out;
        }
        // Each query term contributes at most once — weighted by the strongest place it matched (title
        // over body) — so a note that covers MORE of the question outranks one that merely repeats a
        // single title word. This keeps "title matches weighted higher" without letting one title hit
        // swamp broad coverage.
        double max = terms.size() * (double) TITLE_WEIGHT;
        for (int i = 0; i < nodes.size(); i++) {
            Document d = nodes.get(i);
            if (d == null) {
                continue;
            }
            String title = d.metadata() == null ? "" : d.metadata().getOrDefault("title", "");
            Set<String> titleTerms = Set.copyOf(terms(title));
            Set<String> contentTerms = Set.copyOf(terms(d.content()));
            int raw = 0;
            for (String t : terms) {
                if (titleTerms.contains(t)) {
                    raw += TITLE_WEIGHT;
                } else if (contentTerms.contains(t)) {
                    raw += CONTENT_WEIGHT;
                }
            }
            if (raw > 0) {
                double score = Math.round((raw / max) * 1000.0) / 1000.0;
                out.add(new Scored(i, title, d.content(), score));
            }
        }
        out.sort((a, b) -> {
            int c = Double.compare(b.score(), a.score());
            return c != 0 ? c : Integer.compare(a.id(), b.id());
        });
        return out.size() > topK ? new ArrayList<>(out.subList(0, topK)) : out;
    }

    /** Builds the grounding block prepended to the prompt (empty when there are no hits). */
    static String contextBlock(List<Scored> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
                "[Relevant notes from your knowledge — consult these to answer, and cite the ones "
                        + "you use]\n");
        int n = 1;
        for (Scored h : hits) {
            String title = h.title() == null || h.title().isBlank() ? ("note " + h.id()) : h.title();
            String content = h.content() == null ? "" : h.content().strip();
            if (content.length() > MAX_NOTE_CHARS) {
                content = content.substring(0, MAX_NOTE_CHARS) + "…";
            }
            sb.append(n++).append(". ").append(title);
            if (!content.isBlank()) {
                sb.append(": ").append(content);
            }
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    private static List<String> terms(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) {
            return out;
        }
        for (String tok : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (tok.length() >= 2 && !STOP.contains(tok)) {
                out.add(tok);
            }
        }
        return out;
    }
}
