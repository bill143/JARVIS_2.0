package com.jarvis.rag;

import java.util.List;

/**
 * Read-side retrieval adapter: the module's public contract for finding relevant documents.
 *
 * <p>Adapter-only by design (per spec): concrete backends — vector stores, keyword indexes, hybrid
 * search — implement this interface in later steps or external modules. Callers depend on the
 * interface, never on a backend.
 */
@FunctionalInterface
public interface Retriever {

    /**
     * Returns the documents most relevant to {@code query}, ordered by descending score, with at
     * most {@code query.topK()} entries. An empty list means nothing relevant was found.
     */
    List<ScoredDocument> retrieve(RetrievalQuery query);
}
