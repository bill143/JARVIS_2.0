package com.jarvis.integrations;

import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Instance-wide plugin registration: the plugin/tool adapter pattern from
 * {@code paperclipai/paperclip}, reduced to its mechanism — plugins register once and their tools
 * become available to agents through the shared {@link ToolRegistry}, with no core modification.
 *
 * <p>Installation is validated before any mutation: a duplicate plugin name, a duplicate tool name
 * within the plugin, or a collision with an already-installed tool rejects the whole plugin, so a
 * failed install never leaves a partial contribution behind.
 */
public final class PluginManager {

    private final ToolRegistry tools;
    private final ConcurrentMap<String, Plugin> byName = new ConcurrentHashMap<>();

    /** @param tools the registry installed plugins contribute their tools to */
    public PluginManager(ToolRegistry tools) {
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    /**
     * Registers {@code plugin} and installs its tools into the registry.
     *
     * @throws IllegalArgumentException if the plugin name is already registered, the plugin
     *     declares two tools with the same name, or a declared tool name is already installed
     */
    public synchronized void install(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        PluginDescriptor descriptor =
                Objects.requireNonNull(plugin.descriptor(), "plugin.descriptor()");
        List<Tool> contributed = Objects.requireNonNull(plugin.tools(), "plugin.tools()");
        if (byName.containsKey(descriptor.name())) {
            throw new IllegalArgumentException("plugin already installed: " + descriptor.name());
        }
        Set<String> seen = new HashSet<>();
        for (Tool tool : contributed) {
            String toolName = Objects.requireNonNull(tool, "plugin declared a null tool").name();
            if (!seen.add(toolName)) {
                throw new IllegalArgumentException(
                        "plugin '" + descriptor.name() + "' declares duplicate tool: " + toolName);
            }
            if (tools.lookup(toolName).isPresent()) {
                throw new IllegalArgumentException("plugin '" + descriptor.name()
                        + "' declares tool that is already installed: " + toolName);
            }
        }
        contributed.forEach(tools::register);
        byName.put(descriptor.name(), plugin);
    }

    /** Returns a point-in-time snapshot of the descriptors of all installed plugins. */
    public List<PluginDescriptor> installed() {
        return byName.values().stream().map(Plugin::descriptor).toList();
    }

    /** Returns whether a plugin named {@code name} is installed. */
    public boolean isInstalled(String name) {
        Objects.requireNonNull(name, "name");
        return byName.containsKey(name);
    }
}
