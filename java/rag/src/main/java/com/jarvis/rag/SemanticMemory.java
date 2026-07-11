package com.jarvis.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Recall by meaning. Documents are embedded (when a cloud {@link EmbeddingProvider} is wired) and
 * searched by cosine similarity over a {@link VectorIndex}; a query for "how do I brake" can then
 * surface a note about "stopping distance" that shares no keywords.
 *
 * <p><b>Graceful degradation is the contract.</b> When the embedding provider is dormant (decision
 * D3 — no API key, the default), every document is still stored and {@link #search} silently falls
 * back to keyword overlap via {@link KeywordIndex}. Callers get results either way and never have
 * to branch on whether embeddings are on; {@link #semantic()} reports which mode answered.
 */
public final class SemanticMemory {

    private final EmbeddingProvider embedder;
    private final VectorIndex vectors = new VectorIndex();
    private final KeywordIndex keyword = new KeywordIndex();
    private final ConcurrentMap<String, Document> docs = new ConcurrentHashMap<>();

    /** Creates a semantic memory; a null provider means dormant (keyword-only). */
    public SemanticMemory(EmbeddingProvider embedder) {
        this.embedder = embedder == null ? EmbeddingProvider.DORMANT : embedder;
    }

    /** Whether semantic (embedding) recall is live; false means keyword fallback. */
    public boolean semantic() {
        return embedder.available();
    }

    /** The mode that {@link #search} will use right now: {@code "semantic"} or {@code "keyword"}. */
    public String mode() {
        return semantic() ? "semantic" : "keyword";
    }

    /**
     * Adds (or replaces) a document. It is always keyword-indexed; when embeddings are live it is
     * also embedded into the vector index. An embedding failure is swallowed — the document remains
     * recallable by keyword rather than being lost.
     */
    public void add(Document document) {
        Objects.requireNonNull(document, "document");
        docs.put(document.id(), document);
        keyword.index(document);
        if (embedder.available()) {
            try {
                vectors.put(document.id(), embedder.embed(embedText(document)));
            } catch (Exception e) {
                vectors.remove(document.id());   // keyword recall still covers it
            }
        }
    }

    /** Removes a document from both indexes; returns whether it existed. */
    public boolean remove(String id) {
        vectors.remove(id);
        keyword.remove(id);
        return docs.remove(id) != null;
    }

    /** All stored documents (for listing). */
    public List<Document> all() {
        return new ArrayList<>(docs.values());
    }

    /**
     * Returns up to {@code k} documents most relevant to {@code query}. Uses semantic (cosine)
     * recall when embeddings are live and the query embeds cleanly; otherwise, and on any embedding
     * error, falls back to keyword overlap. Scores are the backend's own (cosine vs. keyword ratio).
     */
    public List<ScoredDocument> search(String query, int k) {
        int limit = Math.max(1, k);
        if (embedder.available()) {
            try {
                float[] q = embedder.embed(query);
                List<ScoredDocument> out = new ArrayList<>();
                for (VectorIndex.Scored hit : vectors.search(q, limit)) {
                    Document d = docs.get(hit.id());
                    if (d != null) {
                        out.add(new ScoredDocument(d, hit.score()));
                    }
                }
                return out;
            } catch (Exception e) {
                // fall through to keyword recall
            }
        }
        return keyword.retrieve(new RetrievalQuery(query, limit));
    }

    private static String embedText(Document d) {
        String title = d.metadata().getOrDefault("title", "");
        String content = d.content() == null ? "" : d.content();
        return title.isBlank() ? content : title + "\n" + content;
    }
}
