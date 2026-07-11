package com.jarvis.tasks;

import java.util.List;
import java.util.Objects;

/**
 * One Kanban task. Immutable — edits produce a new snapshot that {@link TaskBoard} appends.
 *
 * @param id stable identifier
 * @param title short title
 * @param notes longer description
 * @param status which column it's in
 * @param dependsOn ids of tasks that must be DONE before this one is unblocked
 * @param createdAtMillis creation time (epoch millis)
 * @param position sort order within its column
 */
public record Task(String id, String title, String notes, TaskStatus status,
        List<String> dependsOn, long createdAtMillis, int position) {

    public Task {
        Objects.requireNonNull(id, "id");
        title = title == null ? "" : title;
        notes = notes == null ? "" : notes;
        status = status == null ? TaskStatus.TODO : status;
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }

    public Task withStatus(TaskStatus newStatus) {
        return new Task(id, title, notes, newStatus, dependsOn, createdAtMillis, position);
    }
}
