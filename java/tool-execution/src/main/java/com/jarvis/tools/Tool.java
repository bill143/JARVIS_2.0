package com.jarvis.tools;

/**
 * Public contract for an executable tool: the module's extension point. Other modules depend on
 * this interface (and the {@link ToolRegistry} that dispatches to it), never on concrete tools.
 *
 * <p>Implementations should be stateless or internally thread-safe: a single instance may be
 * invoked concurrently. Failures should be reported via {@link ToolResult#error(String)}; thrown
 * exceptions are converted to failure results by the registry.
 */
public interface Tool {

    /** Unique name this tool is registered and dispatched under. */
    String name();

    /** Human/agent-readable description of what the tool does. */
    String description();

    /** Executes the tool against {@code call} and returns the outcome. */
    ToolResult execute(ToolCall call);
}
