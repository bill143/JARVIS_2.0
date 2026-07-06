package com.jarvis.integrations.mark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.integrations.PluginManager;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolRegistry;
import com.jarvis.tools.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemControlPluginTest {

    private final List<List<String>> ran = new ArrayList<>();

    private ToolRegistry onWindows() {
        ToolRegistry registry = new ToolRegistry();
        new PluginManager(registry).install(new SystemControlPlugin("Windows 11", ran::add));
        return registry;
    }

    @Test
    void contributesThreeTools() {
        ToolRegistry registry = onWindows();
        for (String name : new String[] {"app_launch", "volume", "lock_screen"}) {
            assertTrue(registry.lookup(name).isPresent(), name + " missing");
        }
    }

    @Test
    void launchesWhitelistedAppsOnly() {
        ToolRegistry registry = onWindows();

        ToolResult ok = registry.execute(new ToolCall("app_launch", Map.of("app", "Calculator")));
        assertTrue(ok.success());
        assertEquals(List.of("cmd", "/c", "start", "", "calc"), ran.get(0));

        ToolResult bad = registry.execute(new ToolCall("app_launch", Map.of("app", "regedit")));
        assertFalse(bad.success());
        assertTrue(bad.error().contains("allowed:"));
        assertEquals(1, ran.size());
    }

    @Test
    void volumeSynthesizesMediaKeys() {
        ToolRegistry registry = onWindows();

        assertTrue(registry.execute(new ToolCall("volume", Map.of("action", "up"))).success());
        String script = String.join(" ", ran.get(0));
        assertTrue(script.contains("175"));
        assertTrue(script.contains("powershell"));

        assertTrue(registry.execute(new ToolCall("volume", Map.of("action", "mute"))).success());
        assertTrue(String.join(" ", ran.get(1)).contains("173"));

        assertFalse(registry.execute(new ToolCall("volume", Map.of("action", "max"))).success());
    }

    @Test
    void lockScreenUsesTheStandardWindowsCall() {
        ToolRegistry registry = onWindows();
        assertTrue(registry.execute(ToolCall.of("lock_screen")).success());
        assertEquals(List.of("rundll32.exe", "user32.dll,LockWorkStation"), ran.get(0));
    }

    @Test
    void brightnessValidatesRangeAndSetsLevel() {
        ToolRegistry registry = onWindows();
        assertTrue(registry.execute(new ToolCall("brightness", Map.of("level", 40))).success());
        assertTrue(String.join(" ", ran.get(0)).contains("WmiSetBrightness(1,40)"));
        assertFalse(registry.execute(new ToolCall("brightness", Map.of("level", 250))).success());
    }

    @Test
    void wifiStatusAndDisconnect() {
        ToolRegistry registry = onWindows();
        assertTrue(registry.execute(new ToolCall("wifi", Map.of("action", "status"))).success());
        assertEquals(List.of("netsh", "wlan", "show", "interfaces"), ran.get(0));
        assertTrue(registry.execute(new ToolCall("wifi", Map.of("action", "disconnect"))).success());
        assertTrue(registry.execute(new ToolCall("wifi", Map.of("action", "connect"))).success()
                == false);
    }

    @Test
    void powerRestartIsDelayedAndCancellable() {
        ToolRegistry registry = onWindows();
        assertTrue(registry.execute(new ToolCall("power", Map.of("action", "restart"))).success());
        assertTrue(ran.get(0).contains("/t"));
        assertTrue(ran.get(0).contains("15"));
        assertTrue(registry.execute(new ToolCall("power", Map.of("action", "cancel"))).success());
        assertEquals(List.of("shutdown", "/a"), ran.get(1));
        assertFalse(registry.execute(new ToolCall("power", Map.of("action", "explode"))).success());
    }

    @Test
    void hotkeyMapsNamedActions() {
        ToolRegistry registry = onWindows();
        assertTrue(registry.execute(new ToolCall("hotkey", Map.of("action", "copy"))).success());
        assertTrue(String.join(" ", ran.get(0)).contains("^c"));
        assertFalse(registry.execute(new ToolCall("hotkey", Map.of("action", "nope"))).success());
    }

    @Test
    void everythingRefusesOnNonWindows() {
        ToolRegistry registry = new ToolRegistry();
        new PluginManager(registry).install(new SystemControlPlugin("Linux", ran::add));

        for (String name : new String[] {"lock_screen"}) {
            ToolResult result = registry.execute(ToolCall.of(name));
            assertFalse(result.success());
            assertTrue(result.error().contains("Windows"));
        }
        assertTrue(ran.isEmpty());
    }
}
