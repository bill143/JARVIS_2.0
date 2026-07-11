package com.jarvis.workflows;

/** The outcome of one workflow step. */
public record StepResult(String step, String output, boolean ok) {
}
