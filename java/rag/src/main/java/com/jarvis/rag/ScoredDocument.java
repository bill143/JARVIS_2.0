package com.jarvis.rag;

import java.util.Objects;

/**
 * A retrieved document with its relevance score. Score semantics (cosine similarity, BM25, …) are
 * adapter-defined; the contract only requires that higher means more relevant within one result
 * list.
 *
 * @param document the retrieved document
 * @param score adapter-defined relevance; higher is more relevant
 */
public record ScoredDocument(Document document, double score) {

    public ScoredDocument {
        Objects.requireNonNull(document, "document");
        if (Double.isNaN(score)) {
            throw new IllegalArgumentException("score must not be NaN");
        }
    }
}
