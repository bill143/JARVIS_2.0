package com.jarvis.speech;

/**
 * The seam between voice and reasoning: given a transcript, produce the reply to speak. The
 * orchestrator (or any other request handler) plugs in here — the speech module never depends on
 * agent internals.
 */
@FunctionalInterface
public interface PromptHandler {

    /** Handles {@code prompt} and returns the reply text; must not return {@code null}. */
    String handle(String prompt);
}
