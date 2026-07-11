package com.jarvis.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jarvis.tools.RiskTier;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HealthTrackingToolTest {

    private static Tool tool(String name, java.util.function.Function<ToolCall, ToolResult> fn) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "d"; }
            @Override public ToolResult execute(ToolCall call) { return fn.apply(call); }
        };
    }

    @Test
    void recordsSuccessAndFailureIntoTheRegistry() {
        PluginRegistry registry = new PluginRegistry(List.of(
                new ToolManifest("wifi", "", RiskTier.MUTATING, List.of())));
        boolean[] fail = {false};
        Tool tracked = new HealthTrackingTool(
                tool("wifi", c -> fail[0] ? ToolResult.error("off") : ToolResult.ok("on")), registry);

        tracked.execute(new ToolCall("wifi", Map.of()));
        assertEquals(ToolHealth.OPERATIONAL, registry.health("wifi"));
        fail[0] = true;
        tracked.execute(new ToolCall("wifi", Map.of()));
        assertEquals(ToolHealth.DEGRADED, registry.health("wifi"));
        assertEquals(2, registry.stats("wifi").totalCalls());
        assertEquals(1, registry.stats("wifi").failures());
        assertEquals("off", registry.stats("wifi").lastError());
    }

    @Test
    void isTransparentForNameAndDescriptionAndResult() {
        PluginRegistry registry = new PluginRegistry(List.of());
        Tool tracked = new HealthTrackingTool(tool("clock", c -> ToolResult.ok("now")), registry);
        assertEquals("clock", tracked.name());
        assertEquals("d", tracked.description());
        assertEquals("now", tracked.execute(new ToolCall("clock", Map.of())).output());
    }
}
