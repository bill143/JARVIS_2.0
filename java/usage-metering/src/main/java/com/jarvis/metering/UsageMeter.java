package com.jarvis.metering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.memory.RecordStore;
import com.jarvis.memory.StoredRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Provider-agnostic token/cost meter. Each metered call is appended to a {@link RecordStore}
 * collection (the D1 append-only seam — exactly the shape usage data wants), with cost computed
 * from the {@link PriceTable} at record time so history stays stable if prices later change.
 * Aggregates on demand for the HUD widget and history page.
 */
public final class UsageMeter {

    /** Default collection name within the record store. */
    public static final String COLLECTION = "usage";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RecordStore store;
    private final String collection;
    private final PriceTable prices;

    public UsageMeter(RecordStore store, PriceTable prices) {
        this(store, COLLECTION, prices);
    }

    public UsageMeter(RecordStore store, String collection, PriceTable prices) {
        this.store = Objects.requireNonNull(store, "store");
        this.collection = Objects.requireNonNull(collection, "collection");
        this.prices = Objects.requireNonNull(prices, "prices");
    }

    /** Records one call, computing and storing its estimated cost. Returns the stored event. */
    public UsageEvent record(String provider, String model, long inputTokens, long outputTokens) {
        double cost = prices.cost(model, inputTokens, outputTokens);
        UsageEvent event = new UsageEvent(
                Instant.now(), provider, model, inputTokens, outputTokens, cost);
        store.append(collection, serialize(event));
        return event;
    }

    /** The most recent {@code max} events, newest first. */
    public List<UsageEvent> recent(int max) {
        if (max < 0) {
            throw new IllegalArgumentException("max must be >= 0, got " + max);
        }
        List<StoredRecord> tail = store.tail(collection, max);
        List<UsageEvent> out = new ArrayList<>(tail.size());
        for (int i = tail.size() - 1; i >= 0; i--) {   // newest first
            UsageEvent e = deserialize(tail.get(i).payload());
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    /** Totals across every recorded call. */
    public UsageSummary summary() {
        long calls = 0;
        long in = 0;
        long out = 0;
        double cost = 0;
        for (StoredRecord r : store.list(collection)) {
            UsageEvent e = deserialize(r.payload());
            if (e != null) {
                calls++;
                in += e.inputTokens();
                out += e.outputTokens();
                cost += e.costUsd();
            }
        }
        return new UsageSummary(calls, in, out, cost);
    }

    private static String serialize(UsageEvent e) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("at", e.at().toString());
        o.put("provider", e.provider());
        o.put("model", e.model());
        o.put("inputTokens", e.inputTokens());
        o.put("outputTokens", e.outputTokens());
        o.put("costUsd", e.costUsd());
        return o.toString();
    }

    private static UsageEvent deserialize(String payload) {
        try {
            JsonNode n = MAPPER.readTree(payload);
            return new UsageEvent(
                    Instant.parse(n.path("at").asText()),
                    n.path("provider").asText(""),
                    n.path("model").asText(""),
                    n.path("inputTokens").asLong(0),
                    n.path("outputTokens").asLong(0),
                    n.path("costUsd").asDouble(0));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }
}
