package com.jarvis.integrations.mark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.integrations.PluginManager;
import com.jarvis.memory.InMemoryStore;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolRegistry;
import com.jarvis.tools.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarkToolsPluginTest {

    private static ToolRegistry installed() {
        ToolRegistry registry = new ToolRegistry();
        new PluginManager(registry).install(new MarkToolsPlugin(new InMemoryStore<>()));
        return registry;
    }

    @Test
    void pluginContributesAllFiveTools() {
        ToolRegistry registry = installed();
        assertEquals(5, registry.list().size());
        for (String name : new String[] {"clock", "system_info", "reminder_set", "reminder_list", "open_url"}) {
            assertTrue(registry.lookup(name).isPresent(), name + " missing");
        }
    }

    @Test
    void clockTellsTheTimeAndHonorsZones() {
        ToolRegistry registry = installed();

        ToolResult local = registry.execute(ToolCall.of("clock"));
        assertTrue(local.success());
        assertFalse(local.output().isBlank());

        ToolResult zoned = registry.execute(new ToolCall("clock", Map.of("zone", "UTC")));
        assertTrue(zoned.success());
        assertTrue(zoned.output().contains("UTC"));

        ToolResult bad = registry.execute(new ToolCall("clock", Map.of("zone", "Mars/Olympus")));
        assertFalse(bad.success());
    }

    @Test
    void systemInfoReportsThisMachine() {
        ToolResult result = installed().execute(ToolCall.of("system_info"));
        assertTrue(result.success());
        assertTrue(result.output().contains(System.getProperty("os.name")));
        assertTrue(result.output().contains("CPU cores"));
    }

    @Test
    void remindersRoundTripThroughMemory() {
        ToolRegistry registry = installed();

        assertEquals("No reminders saved.",
                registry.execute(ToolCall.of("reminder_list")).output());

        ToolResult saved = registry.execute(new ToolCall("reminder_set",
                Map.of("text", "call the supplier", "when", "tomorrow 9am")));
        assertTrue(saved.success());
        registry.execute(new ToolCall("reminder_set", Map.of("text", "check invoices")));

        String list = registry.execute(ToolCall.of("reminder_list")).output();
        assertTrue(list.contains("call the supplier (when: tomorrow 9am)"));
        assertTrue(list.contains("check invoices"));
    }

    @Test
    void reminderWithoutTextFailsGracefully() {
        ToolResult result = installed().execute(ToolCall.of("reminder_set"));
        assertFalse(result.success());
        assertTrue(result.error().contains("text"));
    }

    @Test
    void openUrlRejectsNonHttpSchemes() {
        ToolResult result = installed().execute(
                new ToolCall("open_url", Map.of("url", "file:///etc/passwd")));
        assertFalse(result.success());
        assertTrue(result.error().contains("http"));
    }
}
