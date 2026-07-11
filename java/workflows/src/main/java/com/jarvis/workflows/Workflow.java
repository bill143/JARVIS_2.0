package com.jarvis.workflows;

import java.util.List;
import java.util.Objects;

/**
 * A named, ordered list of steps (each step is a natural-language instruction run through the agent)
 * plus how it's triggered.
 *
 * @param id stable id
 * @param name human name
 * @param steps ordered step instructions
 * @param trigger how it runs
 * @param intervalSeconds for SCHEDULE triggers, how often to run (ignored otherwise)
 */
public record Workflow(String id, String name, List<String> steps, TriggerType trigger,
        long intervalSeconds) {

    public Workflow {
        Objects.requireNonNull(id, "id");
        name = name == null ? "" : name;
        steps = steps == null ? List.of() : List.copyOf(steps);
        trigger = trigger == null ? TriggerType.MANUAL : trigger;
    }
}
