package com.jarvis.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.tools.RiskTier;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditedToolTest {

    private static Tool tool(String name, java.util.function.Function<ToolCall, ToolResult> fn) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test " + name; }
            @Override public ToolResult execute(ToolCall call) { return fn.apply(call); }
        };
    }

    @Test
    void successfulInvocationIsRecordedWithArgNamesButNotValues() {
        AuditLog log = new RecordStoreAuditLog(new InMemoryRecordStore());
        Tool audited = new AuditedTool(
                tool("email_send", c -> ToolResult.ok("sent")), log,
                RiskTier.DESTRUCTIVE, AuditTrigger.USER);

        ToolResult result = audited.execute(
                new ToolCall("email_send", Map.of("to", "nick@x.com", "body", "SECRET TEXT")));

        assertTrue(result.success());
        AuditEntry entry = log.recent(1).get(0);
        assertEquals("email_send", entry.event().action());
        assertEquals(AuditOutcome.SUCCESS, entry.event().outcome());
        assertEquals(RiskTier.DESTRUCTIVE, entry.event().riskTier());
        assertTrue(entry.event().detail().contains("to"));          // arg name present
        assertTrue(entry.event().detail().contains("body"));
        assertFalse(entry.event().detail().contains("SECRET TEXT")); // value never logged
        assertFalse(entry.event().detail().contains("nick@x.com"));
    }

    @Test
    void failedInvocationIsRecordedWithTheError() {
        AuditLog log = new RecordStoreAuditLog(new InMemoryRecordStore());
        Tool audited = new AuditedTool(
                tool("wifi", c -> ToolResult.error("adapter off")), log);

        audited.execute(new ToolCall("wifi", Map.of()));
        AuditEntry entry = log.recent(1).get(0);
        assertEquals(AuditOutcome.FAILURE, entry.event().outcome());
        assertTrue(entry.event().detail().contains("adapter off"));
    }

    @Test
    void nameAndDescriptionAreTransparent() {
        AuditLog log = new RecordStoreAuditLog(new InMemoryRecordStore());
        Tool audited = new AuditedTool(tool("clock", c -> ToolResult.ok("now")), log);
        assertEquals("clock", audited.name());
        assertEquals("test clock", audited.description());
    }
}
