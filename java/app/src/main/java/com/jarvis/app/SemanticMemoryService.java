package com.jarvis.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.memory.RecordStore;
import com.jarvis.memory.StoredRecord;
import com.jarvis.rag.Document;
import com.jarvis.rag.EmbeddingProvider;
import com.jarvis.rag.ScoredDocument;
import com.jarvis.rag.SemanticMemory;
import com.jarvis.tools.RiskTier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * App facade over {@link SemanticMemory}: durable, audited recall-by-meaning.
 *
 * <p>Memories persist to the append-only {@link RecordStore} seam and are replayed into the
 * semantic index on construction, so recall survives a restart. When a cloud
 * {@link EmbeddingProvider} is wired (decision D3, opt-in) each memory is embedded and searched by
 * meaning; when the provider is dormant the same calls fall back to keyword recall transparently.
 *
 * <p>Every remember/recall is written to the audit log — as an EXTERNAL_API event when embeddings
 * are live (an outbound call is made) and a SYSTEM event otherwise — so the outbound-call boundary
 * (decision D3) is always visible in the HUD activity feed.
 */
final class SemanticMemoryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RecordStore store;
    private final String collection;
    private final SemanticMemory memory;
    private final AuditLog audit;   // nullable
    private int counter;

    SemanticMemoryService(RecordStore store, EmbeddingProvider embedder, AuditLog audit) {
        this(store, "semantic", embedder, audit);
    }

    SemanticMemoryService(RecordStore store, String collection,
            EmbeddingProvider embedder, AuditLog audit) {
        this.store = Objects.requireNonNull(store, "store");
        this.collection = Objects.requireNonNull(collection, "collection");
        this.memory = new SemanticMemory(embedder);
        this.audit = audit;
        replay();
    }

    /** Whether recall is semantic (embeddings live) or keyword (dormant fallback). */
    boolean semantic() {
        return memory.semantic();
    }

    /** Stores a memory (title + content), embeds it when live, and audits it. Returns its id. */
    synchronized String remember(String title, String content, long nowMillis) {
        String id = "sm-" + Long.toHexString(nowMillis) + "-" + (++counter);
        ObjectNode e = MAPPER.createObjectNode();
        e.put("op", "save");
        e.put("id", id);
        e.put("title", title == null ? "" : title);
        e.put("content", content == null ? "" : content);
        store.append(collection, e.toString());
        memory.add(new Document(id, content == null ? "" : content,
                Map.of("title", title == null ? "" : title)));
        record("semantic-remember", "title: " + (title == null ? "" : title));
        return id;
    }

    /** Recalls up to {@code k} memories by meaning (or keyword when dormant); audits the recall. */
    synchronized List<ScoredDocument> recall(String query, int k) {
        List<ScoredDocument> hits = memory.search(query, k);
        record("semantic-recall", "q: " + query + " (" + memory.mode() + ", " + hits.size() + ")");
        return hits;
    }

    /** Removes a stored memory by id (append-only delete op); returns whether it was present. */
    synchronized boolean forget(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        ObjectNode e = MAPPER.createObjectNode();
        e.put("op", "delete");
        e.put("id", id);
        store.append(collection, e.toString());
        boolean removed = memory.remove(id);
        record("semantic-forget", "id: " + id);
        return removed;
    }

    /**
     * Updates a stored memory in place (edit): the id is preserved, the title/content replaced.
     * Implemented as a delete followed by a re-save of the same id so the append-only log stays
     * consistent and recall reflects the new text immediately. Returns whether the id existed.
     */
    synchronized boolean update(String id, String title, String content, long nowMillis) {
        if (id == null || id.isBlank() || !memory.all().stream().anyMatch(d -> d.id().equals(id))) {
            return false;
        }
        ObjectNode del = MAPPER.createObjectNode();
        del.put("op", "delete");
        del.put("id", id);
        store.append(collection, del.toString());
        memory.remove(id);
        ObjectNode e = MAPPER.createObjectNode();
        e.put("op", "save");
        e.put("id", id);
        e.put("title", title == null ? "" : title);
        e.put("content", content == null ? "" : content);
        store.append(collection, e.toString());
        memory.add(new Document(id, content == null ? "" : content,
                Map.of("title", title == null ? "" : title)));
        record("semantic-update", "id: " + id);
        return true;
    }

    /**
     * Mirrors the Brain/Obsidian vault into the unified store so notes are recalled alongside memory
     * and intelligence facts. Vault notes get stable ids ({@code vault-<relpath>}) so re-syncing
     * updates in place; notes removed from the vault are forgotten. Idempotent.
     */
    synchronized int syncVault(List<Document> notes) {
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Document n : notes) {
            String id = "vault-" + n.id();
            keep.add(id);
            String title = n.metadata().getOrDefault("title", n.id());
            ObjectNode e = MAPPER.createObjectNode();
            e.put("op", "save");
            e.put("id", id);
            e.put("title", title);
            e.put("content", n.content());
            store.append(collection, e.toString());
            memory.remove(id);
            memory.add(new Document(id, n.content(), Map.of("title", title, "source", "vault")));
        }
        // Forget vault notes that no longer exist on disk.
        for (Document d : memory.all()) {
            if (d.id().startsWith("vault-") && !keep.contains(d.id())) {
                ObjectNode del = MAPPER.createObjectNode();
                del.put("op", "delete");
                del.put("id", d.id());
                store.append(collection, del.toString());
                memory.remove(d.id());
            }
        }
        record("semantic-vault-sync", notes.size() + " vault notes");
        return notes.size();
    }

    /** All stored memories (for listing). */
    List<Document> all() {
        return memory.all();
    }

    static String titleOf(Document d) {
        return d.metadata().getOrDefault("title", "");
    }

    private void record(String action, String detail) {
        if (audit == null) {
            return;
        }
        AuditCategory category = memory.semantic() ? AuditCategory.EXTERNAL_API : AuditCategory.SYSTEM;
        audit.record(new AuditEvent(category, action, AuditTrigger.USER,
                RiskTier.READ_ONLY, AuditOutcome.SUCCESS, detail));
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
            counter++;
            if ("delete".equals(e.path("op").asText())) {
                current.remove(e.path("id").asText());
            } else if (e.has("id")) {
                current.put(e.path("id").asText(), new Document(e.path("id").asText(),
                        e.path("content").asText(""), Map.of("title", e.path("title").asText(""))));
            }
        }
        current.values().forEach(memory::add);
    }
}
