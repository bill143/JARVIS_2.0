package com.jarvis.tools;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable request to execute a named tool with a set of arguments.
 *
 * <p>Arguments are a generic name/value map so the execution contract stays domain-free; individual
 * tools define and validate the argument shapes they accept.
 *
 * @param toolName name of the tool to execute
 * @param arguments immutable, non-null map of argument name to value
 */
public record ToolCall(String toolName, Map<String, Object> arguments) {

    public ToolCall {
        Objects.requireNonNull(toolName, "toolName");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    /** Creates a call with no arguments. */
    public static ToolCall of(String toolName) {
        return new ToolCall(toolName, Map.of());
    }
}
