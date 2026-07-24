package com.jarvis.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe registry and dispatcher for {@link Tool}s, backed by {@link ConcurrentHashMap}.
 *
 * <p>Tools are registered under their {@link Tool#name()}. Registration is first-wins: a duplicate
 * name fails fast with {@link IllegalArgumentException} rather than silently replacing a tool.
 * Dispatch never throws for tool-level problems — unknown names and exceptions thrown by a tool
 * are both surfaced as failed {@link ToolResult}s so callers handle one error channel.
 */
public final class ToolRegistry {

    private final ConcurrentMap<String, Tool> byName = new ConcurrentHashMap<>();

    /**
     * Registers {@code tool} under its name.
     *
     * @throws IllegalArgumentException if a tool with the same name is already registered
     */
    public void register(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        String name = Objects.requireNonNull(tool.name(), "tool.name()");
        Tool existing = byName.putIfAbsent(name, tool);
        if (existing != null) {
            throw new IllegalArgumentException("tool already registered: " + name);
        }
    }

    /** Returns the tool registered under {@code name}, if any. */
    public Optional<Tool> lookup(String name) {
        Objects.requireNonNull(name, "name");
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * Removes the tool registered under {@code name}, if any. Supports dynamically-scoped tools
     * (e.g. MCP server tools that disappear when their server is removed).
     *
     * @return whether a tool was registered under that name
     */
    public boolean deregister(String name) {
        Objects.requireNonNull(name, "name");
        return byName.remove(name) != null;
    }

    /** Returns a point-in-time snapshot of all registered tools. */
    public List<Tool> list() {
        return new ArrayList<>(byName.values());
    }

    /**
     * Looks up the tool named by {@code call} and executes it. Unknown tool names and exceptions
     * thrown during execution are returned as failed results, never propagated.
     */
    public ToolResult execute(ToolCall call) {
        Objects.requireNonNull(call, "call");
        Tool tool = byName.get(call.toolName());
        if (tool == null) {
            return ToolResult.error("unknown tool: " + call.toolName());
        }
        try {
            return Objects.requireNonNull(
                    tool.execute(call), "tool returned null result: " + call.toolName());
        } catch (RuntimeException e) {
            return ToolResult.error("tool '" + call.toolName() + "' threw: " + e);
        }
    }
}
