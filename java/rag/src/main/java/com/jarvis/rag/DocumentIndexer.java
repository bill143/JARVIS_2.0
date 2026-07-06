package com.jarvis.rag;

/**
 * Write-side retrieval adapter: how documents enter and leave a retrieval backend.
 *
 * <p>Separated from {@link Retriever} so read-only consumers (the agent loop, orchestration) never
 * see indexing operations. A backend typically implements both interfaces.
 */
public interface DocumentIndexer {

    /**
     * Adds {@code document} to the index, replacing any previously indexed document with the same
     * id.
     */
    void index(Document document);

    /**
     * Removes the document with {@code id} from the index.
     *
     * @return {@code true} if a document was removed, {@code false} if none existed
     */
    boolean remove(String id);
}
