package com.jarvis.registry;

import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.util.Objects;

/**
 * Wraps a {@link Tool} so each invocation updates its health in a {@link PluginRegistry}. Transparent
 * to the agent loop — same name, description, and result — it just tallies success vs. failure so
 * the registry can report OPERATIONAL / DEGRADED / CIRCUIT_OPEN.
 */
public final class HealthTrackingTool implements Tool {

    private final Tool delegate;
    private final PluginRegistry registry;

    public HealthTrackingTool(Tool delegate, PluginRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            ToolResult result = delegate.execute(call);
            if (result.success()) {
                registry.recordSuccess(delegate.name());
            } else {
                registry.recordFailure(delegate.name(), result.error());
            }
            return result;
        } catch (RuntimeException e) {
            registry.recordFailure(delegate.name(), e.toString());
            throw e;
        }
    }
}
