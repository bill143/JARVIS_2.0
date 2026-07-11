package com.jarvis.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.memory.RecordStore;
import com.jarvis.memory.StoredRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Event-sourced store of workflow definitions on the {@link RecordStore} seam. */
public final class WorkflowStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RecordStore store;
    private final String collection;

    public WorkflowStore(RecordStore store) {
        this(store, "workflows");
    }

    public WorkflowStore(RecordStore store, String collection) {
        this.store = Objects.requireNonNull(store, "store");
        this.collection = Objects.requireNonNull(collection, "collection");
    }

    public synchronized Workflow save(Workflow wf) {
        ObjectNode e = MAPPER.createObjectNode();
        e.put("op", "save");
        e.set("wf", encode(wf));
        store.append(collection, e.toString());
        return wf;
    }

    public synchronized void delete(String id) {
        ObjectNode e = MAPPER.createObjectNode();
        e.put("op", "delete");
        e.put("id", id);
        store.append(collection, e.toString());
    }

    public List<Workflow> list() {
        Map<String, Workflow> byId = new LinkedHashMap<>();
        for (StoredRecord r : store.list(collection)) {
            JsonNode e;
            try {
                e = MAPPER.readTree(r.payload());
            } catch (Exception skip) {
                continue;
            }
            if ("delete".equals(e.path("op").asText())) {
                byId.remove(e.path("id").asText());
            } else if (e.has("wf")) {
                Workflow w = decode(e.get("wf"));
                if (w != null) {
                    byId.put(w.id(), w);
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    public java.util.Optional<Workflow> get(String id) {
        return list().stream().filter(w -> w.id().equals(id)).findFirst();
    }

    private static ObjectNode encode(Workflow w) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("id", w.id());
        o.put("name", w.name());
        o.put("trigger", w.trigger().name());
        o.put("intervalSeconds", w.intervalSeconds());
        ArrayNode steps = o.putArray("steps");
        w.steps().forEach(steps::add);
        return o;
    }

    private static Workflow decode(JsonNode n) {
        try {
            List<String> steps = new ArrayList<>();
            n.path("steps").forEach(s -> steps.add(s.asText()));
            TriggerType trigger;
            try {
                trigger = TriggerType.valueOf(n.path("trigger").asText("MANUAL").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                trigger = TriggerType.MANUAL;
            }
            return new Workflow(n.path("id").asText(), n.path("name").asText(""), steps, trigger,
                    n.path("intervalSeconds").asLong(0));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
