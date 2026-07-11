package com.jarvis.rag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A concrete, dependency-free retriever/indexer that lights up the {@code rag} contracts: it scores
 * documents by keyword overlap with the query. Simple and honest — not semantic (that arrives with
 * cloud embeddings in the semantic-memory step); good enough for a personal knowledge base.
 */
public final class KeywordIndex implements Retriever, DocumentIndexer {

    private final ConcurrentMap<String, Document> docs = new ConcurrentHashMap<>();

    @Override
    public void index(Document document) {
        docs.put(document.id(), document);
    }

    @Override
    public boolean remove(String id) {
        return docs.remove(id) != null;
    }

    @Override
    public List<ScoredDocument> retrieve(RetrievalQuery query) {
        Set<String> terms = tokenize(query.text());
        if (terms.isEmpty()) {
            return List.of();
        }
        List<ScoredDocument> scored = new ArrayList<>();
        for (Document d : docs.values()) {
            String hay = ((d.content() == null ? "" : d.content()) + " "
                    + d.metadata().getOrDefault("title", "")).toLowerCase(Locale.ROOT);
            long hits = terms.stream().filter(hay::contains).count();
            if (hits > 0) {
                scored.add(new ScoredDocument(d, (double) hits / terms.size()));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());
        int k = Math.max(0, query.topK());
        return scored.size() > k ? new ArrayList<>(scored.subList(0, k)) : scored;
    }

    /** All indexed documents (for listing). */
    public List<Document> all() {
        return new ArrayList<>(docs.values());
    }

    private static Set<String> tokenize(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() >= 2) {
                out.add(token);
            }
        }
        out.removeAll(Arrays.asList("the", "and", "for", "with", "that", "this", "you", "are"));
        return out;
    }
}
