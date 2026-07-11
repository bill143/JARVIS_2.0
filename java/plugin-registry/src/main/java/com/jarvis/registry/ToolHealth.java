package com.jarvis.registry;

/**
 * Live health of a tool, derived from its recent success/failure record.
 *
 * <ul>
 *   <li>{@link #OPERATIONAL} — no consecutive failures.</li>
 *   <li>{@link #DEGRADED} — some recent failures, but below the circuit threshold.</li>
 *   <li>{@link #CIRCUIT_OPEN} — failures reached the threshold; the tool is considered unhealthy.</li>
 * </ul>
 */
public enum ToolHealth {
    OPERATIONAL,
    DEGRADED,
    CIRCUIT_OPEN
}
