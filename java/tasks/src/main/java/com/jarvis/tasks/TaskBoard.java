package com.jarvis.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.memory.RecordStore;
import com.jarvis.memory.StoredRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Event-sourced Kanban board on the append-only {@link RecordStore} seam: every {@code save}/
 * {@code delete} appends an event, and {@link #list()} folds the log into the current tasks. Simple,
 * durable, and audit-friendly — the full history of a task lives in the log.
 */
public final class TaskBoard {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RecordStore store;
    private final String collection;

    public TaskBoard(RecordStore store) {
        this(store, "tasks");
    }

    public TaskBoard(RecordStore store, String collection) {
        this.store = Objects.requireNonNull(store, "store");
        this.collection = Objects.requireNonNull(collection, "collection");
    }

    /** Creates a new TODO task at the end of the board. */
    public synchronized Task create(String title, String notes, List<String> dependsOn, long nowMillis) {
        String id = "t-" + Long.toHexString(nowMillis) + "-" + (list().size() + 1);
        Task task = new Task(id, title, notes, TaskStatus.TODO, dependsOn, nowMillis, list().size());
        return save(task);
    }

    /** Appends a full snapshot of {@code task} (create or edit). Returns it. */
    public synchronized Task save(Task task) {
        Objects.requireNonNull(task, "task");
        ObjectNode e = MAPPER.createObjectNode();
        e.put("op", "save");
        e.set("task", encode(task));
        store.append(collection, e.toString());
        return task;
    }

    /** Moves a task to a new column, if it exists. */
    public synchronized void move(String id, TaskStatus status) {
        list().stream().filter(t -> t.id().equals(id)).findFirst()
                .ifPresent(t -> save(t.withStatus(status)));
    }

    /** Removes a task. */
    public synchronized void delete(String id) {
        ObjectNode e = MAPPER.createObjectNode();
        e.put("op", "delete");
        e.put("id", id);
        store.append(collection, e.toString());
    }

    /** Current tasks, ordered by column then position then creation. */
    public List<Task> list() {
        Map<String, Task> byId = new LinkedHashMap<>();
        for (StoredRecord r : store.list(collection)) {
            JsonNode e;
            try {
                e = MAPPER.readTree(r.payload());
            } catch (Exception skip) {
                continue;
            }
            if ("delete".equals(e.path("op").asText())) {
                byId.remove(e.path("id").asText());
            } else if (e.has("task")) {
                Task t = decode(e.get("task"));
                if (t != null) {
                    byId.put(t.id(), t);
                }
            }
        }
        List<Task> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparing((Task t) -> t.status().ordinal())
                .thenComparingInt(Task::position)
                .thenComparingLong(Task::createdAtMillis));
        return out;
    }

    /** Whether {@code task} is blocked: it has a dependency that isn't DONE. */
    public static boolean isBlocked(Task task, List<Task> all) {
        if (task.dependsOn().isEmpty() || task.status() == TaskStatus.DONE) {
            return false;
        }
        Map<String, Task> byId = new LinkedHashMap<>();
        all.forEach(t -> byId.put(t.id(), t));
        for (String dep : task.dependsOn()) {
            Task d = byId.get(dep);
            if (d == null || d.status() != TaskStatus.DONE) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode encode(Task t) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("id", t.id());
        o.put("title", t.title());
        o.put("notes", t.notes());
        o.put("status", t.status().name());
        o.put("createdAtMillis", t.createdAtMillis());
        o.put("position", t.position());
        ArrayNode deps = o.putArray("dependsOn");
        t.dependsOn().forEach(deps::add);
        return o;
    }

    private static Task decode(JsonNode n) {
        try {
            List<String> deps = new ArrayList<>();
            n.path("dependsOn").forEach(d -> deps.add(d.asText()));
            TaskStatus status;
            try {
                status = TaskStatus.valueOf(n.path("status").asText("TODO").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                status = TaskStatus.TODO;
            }
            return new Task(n.path("id").asText(), n.path("title").asText(""),
                    n.path("notes").asText(""), status, deps,
                    n.path("createdAtMillis").asLong(0), n.path("position").asInt(0));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
