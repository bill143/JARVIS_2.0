package com.jarvis.audit;

import java.time.Instant;
import java.util.Objects;

/**
 * A recorded {@link AuditEvent} plus the identity the log assigned it: a monotonic sequence and the
 * instant it was recorded. This is what queries return.
 *
 * @param seq 0-based position in the log
 * @param at when the event was recorded
 * @param event what happened
 */
public record AuditEntry(long seq, Instant at, AuditEvent event) {

    public AuditEntry {
        if (seq < 0) {
            throw new IllegalArgumentException("seq must be >= 0, got " + seq);
        }
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(event, "event");
    }
}
