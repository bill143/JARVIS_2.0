package com.jarvis.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolRegistry;
import com.jarvis.tools.ToolResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginManagerTest {

    private static Tool tool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test tool " + name;
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.ok("ran " + name);
            }
        };
    }

    private static Plugin plugin(String name, Tool... tools) {
        return new Plugin() {
            @Override
            public PluginDescriptor descriptor() {
                return new PluginDescriptor(name, "1.0.0", "test plugin " + name);
            }

            @Override
            public List<Tool> tools() {
                return List.of(tools);
            }
        };
    }

    @Test
    void installedPluginToolsAreDispatchableThroughTheRegistry() {
        ToolRegistry registry = new ToolRegistry();
        PluginManager manager = new PluginManager(registry);

        manager.install(plugin("weather", tool("forecast"), tool("radar")));

        assertTrue(manager.isInstalled("weather"));
        assertEquals(2, registry.list().size());
        assertEquals(ToolResult.ok("ran forecast"), registry.execute(ToolCall.of("forecast")));
    }

    @Test
    void installedDescriptorsAreListed() {
        PluginManager manager = new PluginManager(new ToolRegistry());
        manager.install(plugin("a", tool("t1")));
        manager.install(plugin("b", tool("t2")));

        List<String> names = manager.installed().stream().map(PluginDescriptor::name).sorted().toList();
        assertEquals(List.of("a", "b"), names);
    }

    @Test
    void duplicatePluginNameIsRejected() {
        PluginManager manager = new PluginManager(new ToolRegistry());
        manager.install(plugin("dup", tool("t1")));

        assertThrows(IllegalArgumentException.class, () -> manager.install(plugin("dup", tool("t2"))));
    }

    @Test
    void toolCollisionRejectsThePluginWithoutPartialInstall() {
        ToolRegistry registry = new ToolRegistry();
        PluginManager manager = new PluginManager(registry);
        manager.install(plugin("first", tool("shared")));

        // Second plugin declares a fresh tool AND a colliding one — nothing may be installed.
        assertThrows(IllegalArgumentException.class,
                () -> manager.install(plugin("second", tool("fresh"), tool("shared"))));

        assertFalse(manager.isInstalled("second"));
        assertTrue(registry.lookup("fresh").isEmpty());
        assertEquals(1, registry.list().size());
    }

    @Test
    void duplicateToolNamesWithinOnePluginAreRejected() {
        PluginManager manager = new PluginManager(new ToolRegistry());
        assertThrows(IllegalArgumentException.class,
                () -> manager.install(plugin("p", tool("same"), tool("same"))));
        assertFalse(manager.isInstalled("p"));
    }

    @Test
    void pluginWithNoToolsIsStillInstallable() {
        ToolRegistry registry = new ToolRegistry();
        PluginManager manager = new PluginManager(registry);
        manager.install(plugin("marker-only"));

        assertTrue(manager.isInstalled("marker-only"));
        assertTrue(registry.list().isEmpty());
    }

    @Test
    void descriptorValidation() {
        assertThrows(IllegalArgumentException.class, () -> new PluginDescriptor(" ", "1", "d"));
        assertThrows(NullPointerException.class, () -> new PluginDescriptor(null, "1", "d"));
        assertThrows(NullPointerException.class, () -> new PluginDescriptor("n", null, "d"));
        assertThrows(NullPointerException.class, () -> new PluginDescriptor("n", "1", null));
    }

    @Test
    void managerValidatesArguments() {
        PluginManager manager = new PluginManager(new ToolRegistry());
        assertThrows(NullPointerException.class, () -> new PluginManager(null));
        assertThrows(NullPointerException.class, () -> manager.install(null));
        assertThrows(NullPointerException.class, () -> manager.isInstalled(null));
    }
}
