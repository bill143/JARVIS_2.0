package com.jarvis.security;

/**
 * How aggressively JARVIS asks before acting. Chosen by the user in Settings.
 *
 * <ul>
 *   <li>{@link #OFF} — never prompt (trust the assistant fully).</li>
 *   <li>{@link #DESTRUCTIVE} — prompt only before destructive / unclassified actions (the default:
 *       gates the irreversible things — send/delete mail, power off — without nagging on reversible
 *       tweaks like volume).</li>
 *   <li>{@link #MUTATING} — prompt before anything that changes state (mutating <em>and</em>
 *       destructive). Safest, most interruptive.</li>
 * </ul>
 */
public enum PermissionLevel {
    OFF,
    DESTRUCTIVE,
    MUTATING
}
