package com.jarvis.tools;

import java.util.Objects;

/**
 * Immutable outcome of a tool execution: either a successful output or a failure error message,
 * never both. Use the {@link #ok(String)} and {@link #error(String)} factories.
 *
 * @param success whether the execution succeeded
 * @param output the tool's output; non-null exactly when {@code success} is {@code true}
 * @param error the failure message; non-null exactly when {@code success} is {@code false}
 */
public record ToolResult(boolean success, String output, String error) {

    public ToolResult {
        if (success) {
            Objects.requireNonNull(output, "output must be non-null on success");
            if (error != null) {
                throw new IllegalArgumentException("error must be null on success");
            }
        } else {
            Objects.requireNonNull(error, "error must be non-null on failure");
            if (output != null) {
                throw new IllegalArgumentException("output must be null on failure");
            }
        }
    }

    /** Creates a successful result carrying {@code output}. */
    public static ToolResult ok(String output) {
        return new ToolResult(true, output, null);
    }

    /** Creates a failed result carrying {@code error}. */
    public static ToolResult error(String error) {
        return new ToolResult(false, null, error);
    }
}
