package com.jarvis.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.memory.RecordStore;
import com.jarvis.memory.StoredRecord;
import com.jarvis.tools.RiskTier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link AuditLog} backed by a {@link RecordStore} — the D1 storage seam from Phase 0. Each event
 * is serialized to a one-line JSON payload and appended to a single collection; the store supplies
 * the monotonic sequence and timestamp. Durable when the store is ({@code FileRecordStore}),
 * ephemeral when it isn't ({@code InMemoryRecordStore}), with no change here.
 */
public final class RecordStoreAuditLog implements AuditLog {

    /** Default collection name within the record store. */
    public static final String COLLECTION = "audit";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RecordStore store;
    private final String collection;

    public RecordStoreAuditLog(RecordStore store) {
        this(store, COLLECTION);
    }

    public RecordStoreAuditLog(RecordStore store, String collection) {
        this.store = Objects.requireNonNull(store, "store");
        this.collection = Objects.requireNonNull(collection, "collection");
    }

    @Override
    public AuditEntry record(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        StoredRecord stored = store.append(collection, serialize(event));
        return new AuditEntry(stored.seq(), stored.at(), event);
    }

    @Override
    public List<AuditEntry> query(AuditQuery query) {
        Objects.requireNonNull(query, "query");
        List<AuditEntry> out = new ArrayList<>();
        for (StoredRecord r : store.list(collection)) {
            AuditEntry entry = toEntry(r);
            if (entry != null && query.matches(entry)) {
                out.add(entry);
            }
        }
        return out;
    }

    @Override
    public List<AuditEntry> recent(int max) {
        if (max < 0) {
            throw new IllegalArgumentException("max must be >= 0, got " + max);
        }
        List<StoredRecord> tail = store.tail(collection, max);
        List<AuditEntry> out = new ArrayList<>(tail.size());
        for (int i = tail.size() - 1; i >= 0; i--) {   // newest first
            AuditEntry entry = toEntry(tail.get(i));
            if (entry != null) {
                out.add(entry);
            }
        }
        return out;
    }

    private AuditEntry toEntry(StoredRecord record) {
        AuditEvent event = deserialize(record.payload());
        return event == null ? null : new AuditEntry(record.seq(), record.at(), event);
    }

    private static String serialize(AuditEvent event) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("category", event.category().name());
        node.put("action", event.action());
        node.put("trigger", event.trigger().name());
        node.put("riskTier", event.riskTier().name());
        node.put("outcome", event.outcome().name());
        node.put("detail", event.detail());
        return node.toString();
    }

    /** Parses a stored payload; returns null (skip the line) if it is unreadable. */
    private static AuditEvent deserialize(String payload) {
        try {
            JsonNode n = MAPPER.readTree(payload);
            return new AuditEvent(
                    enumOr(AuditCategory.class, n.path("category").asText(), AuditCategory.SYSTEM),
                    n.path("action").asText(""),
                    enumOr(AuditTrigger.class, n.path("trigger").asText(), AuditTrigger.SYSTEM),
                    enumOr(RiskTier.class, n.path("riskTier").asText(), RiskTier.UNKNOWN),
                    enumOr(AuditOutcome.class, n.path("outcome").asText(), AuditOutcome.SUCCESS),
                    n.path("detail").asText(""));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return fallback;
        }
    }
}
