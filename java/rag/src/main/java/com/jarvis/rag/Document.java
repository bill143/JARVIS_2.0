package com.jarvis.rag;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable unit of retrievable content.
 *
 * @param id unique identifier of the document
 * @param content the retrievable text
 * @param metadata immutable, non-null map of auxiliary attributes (source, tags, …)
 */
public record Document(String id, String content, Map<String, String> metadata) {

    public Document {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(content, "content");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Creates a document with no metadata. */
    public static Document of(String id, String content) {
        return new Document(id, content, Map.of());
    }
}
