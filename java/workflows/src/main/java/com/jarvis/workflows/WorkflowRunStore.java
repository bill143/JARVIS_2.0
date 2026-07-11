package com.jarvis.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.memory.RecordStore;
import com.jarvis.memory.StoredRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Append-only history of workflow runs on the {@link RecordStore} seam. */
public final class WorkflowRunStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RecordStore store;
    private final String collection;

    public WorkflowRunStore(RecordStore store) {
        this(store, "workflow-runs");
    }

    public WorkflowRunStore(RecordStore store, String collection) {
        this.store = Objects.requireNonNull(store, "store");
        this.collection = Objects.requireNonNull(collection, "collection");
    }

    public void append(WorkflowRun run) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("id", run.id());
        o.put("workflowId", run.workflowId());
        o.put("workflowName", run.workflowName());
        o.put("startedAtMillis", run.startedAtMillis());
        o.put("trigger", run.trigger().name());
        o.put("ok", run.ok());
        ArrayNode steps = o.putArray("steps");
        for (StepResult s : run.steps()) {
            ObjectNode so = steps.addObject();
            so.put("step", s.step());
            so.put("output", s.output());
            so.put("ok", s.ok());
        }
        store.append(collection, o.toString());
    }

    /** Recent runs, newest first. */
    public List<WorkflowRun> recent(int max) {
        List<StoredRecord> tail = store.tail(collection, max);
        List<WorkflowRun> out = new ArrayList<>(tail.size());
        for (int i = tail.size() - 1; i >= 0; i--) {
            WorkflowRun r = decode(tail.get(i).payload());
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    private static WorkflowRun decode(String payload) {
        try {
            JsonNode n = MAPPER.readTree(payload);
            List<StepResult> steps = new ArrayList<>();
            n.path("steps").forEach(s -> steps.add(new StepResult(
                    s.path("step").asText(""), s.path("output").asText(""), s.path("ok").asBoolean(false))));
            TriggerType trigger;
            try {
                trigger = TriggerType.valueOf(n.path("trigger").asText("MANUAL").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                trigger = TriggerType.MANUAL;
            }
            return new WorkflowRun(n.path("id").asText(), n.path("workflowId").asText(""),
                    n.path("workflowName").asText(""), n.path("startedAtMillis").asLong(0),
                    trigger, steps, n.path("ok").asBoolean(false));
        } catch (Exception e) {
            return null;
        }
    }
}
