package com.jarvis.rag;

import java.util.Objects;

/**
 * Immutable retrieval request.
 *
 * @param text what to retrieve for
 * @param topK maximum number of results wanted; at least 1
 */
public record RetrievalQuery(String text, int topK) {

    public RetrievalQuery {
        Objects.requireNonNull(text, "text");
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be at least 1, got " + topK);
        }
    }
}
