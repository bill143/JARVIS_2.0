package com.jarvis.audit;

import java.util.List;

/**
 * Structured, queryable local record of every tool invocation, destructive action, and external
 * API call. The backbone of Phase 1 governance: the permission layer writes its decisions here and
 * the HUD error/status feed reads from here as the single source of truth.
 *
 * <p>Append-only by design — entries are never edited or deleted, so the log is a faithful history.
 */
public interface AuditLog {

    /**
     * Records {@code event}, stamping it with the next sequence and the current time.
     *
     * @return the stored entry
     */
    AuditEntry record(AuditEvent event);

    /** Returns entries matching {@code query} in chronological order (oldest first). */
    List<AuditEntry> query(AuditQuery query);

    /** Returns the most recent {@code max} entries, newest first. */
    List<AuditEntry> recent(int max);
}
