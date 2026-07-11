package com.jarvis.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.memory.RecordStore;
import com.jarvis.memory.StoredRecord;
import com.jarvis.rag.Document;
import com.jarvis.rag.KeywordIndex;
import com.jarvis.rag.RetrievalQuery;
import com.jarvis.rag.ScoredDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A durable knowledge base: documents persist to the append-only {@link RecordStore} seam and are
 * kept in a {@link KeywordIndex} for retrieval. On construction it replays the log into the index,
 * so search works immediately after a restart. Titles are stored in document metadata.
 */
public final class KnowledgeBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RecordStore store;
    private final String collection;
    private final KeywordIndex index = new KeywordIndex();

    public KnowledgeBase(RecordStore store) {
        this(store, "knowledge");
    }

    public KnowledgeBase(RecordStore store, String collection) {
        this.store = Objects.requireNonNull(store, "store");
        this.collection = Objects.requireNonNull(collection, "collection");
        replay();
    }

    /** Adds a document and indexes it. Returns its id. */
    public synchronized String add(String title, String content, long nowMillis) {
        String id = "kb-" + Long.toHexString(nowMillis) + "-" + (index.all().size() + 1);
        Document doc = new Document(id, content == null ? "" : content,
                Map.of("title", title == null ? "" : title));
        ObjectNode e = MAPPER.createObjectNode();
        e.put("op", "save");
        e.put("id", id);
        e.put("title", doc.metadata().get("title"));
        e.put("content", doc.content());
        store.append(collection, e.toString());
        index.index(doc);
        return id;
    }

    /** Removes a document. */
    public synchronized void delete(String id) {
        ObjectNode e = MAPPER.createObjectNode();
        e.put("op", "delete");
        e.put("id", id);
        store.append(collection, e.toString());
        index.remove(id);
    }

    /** Top {@code topK} documents relevant to {@code query}, by keyword overlap. */
    public List<ScoredDocument> search(String query, int topK) {
        return index.retrieve(new RetrievalQuery(query, topK));
    }

    /** All documents currently in the base. */
    public List<Document> list() {
        return index.all();
    }

    public static String titleOf(Document d) {
        return d.metadata().getOrDefault("title", "");
    }

    private void replay() {
        Map<String, Document> current = new LinkedHashMap<>();
        for (StoredRecord r : store.list(collection)) {
            JsonNode e;
            try {
                e = MAPPER.readTree(r.payload());
            } catch (Exception skip) {
                continue;
            }
            if ("delete".equals(e.path("op").asText())) {
                current.remove(e.path("id").asText());
            } else if (e.has("id")) {
                current.put(e.path("id").asText(), new Document(e.path("id").asText(),
                        e.path("content").asText(""), Map.of("title", e.path("title").asText(""))));
            }
        }
        current.values().forEach(index::index);
    }
}
