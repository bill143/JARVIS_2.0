package com.jarvis.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.audit.AuditLog;
import com.jarvis.audit.RecordStoreAuditLog;
import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.tools.RiskTier;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AuthorizingToolTest {

    private static Tool tool(String name, AtomicBoolean ran) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "d"; }
            @Override public ToolResult execute(ToolCall call) {
                ran.set(true);
                return ToolResult.ok("done");
            }
        };
    }

    @Test
    void readOnlyToolRunsWithoutPromptingOrExtraAuditEntry() {
        AuditLog log = new RecordStoreAuditLog(new InMemoryRecordStore());
        AtomicBoolean ran = new AtomicBoolean();
        PermissionGate never = (t, tier, d) -> { throw new AssertionError("should not prompt"); };
        Tool authed = new AuthorizingTool(tool("clock", ran), RiskTier.READ_ONLY,
                new PermissionPolicy(), never, log);

        assertTrue(authed.execute(new ToolCall("clock", Map.of())).success());
        assertTrue(ran.get());
        assertTrue(log.recent(10).isEmpty());   // no permission entry for read-only
    }

    @Test
    void destructiveToolRunsWhenApprovedAndLogsTheDecision() {
        AuditLog log = new RecordStoreAuditLog(new InMemoryRecordStore());
        AtomicBoolean ran = new AtomicBoolean();
        PermissionGate approve = (t, tier, d) -> PermissionOutcome.ALLOWED;
        Tool authed = new AuthorizingTool(tool("email_send", ran), RiskTier.DESTRUCTIVE,
                new PermissionPolicy(), approve, log);

        assertTrue(authed.execute(new ToolCall("email_send", Map.of("to", "x"))).success());
        assertTrue(ran.get());
        assertEquals(1, log.recent(10).size());
        assertTrue(log.recent(1).get(0).event().detail().contains("ALLOWED"));
    }

    @Test
    void destructiveToolIsBlockedWhenDeniedAndNeverExecutes() {
        AuditLog log = new RecordStoreAuditLog(new InMemoryRecordStore());
        AtomicBoolean ran = new AtomicBoolean();
        PermissionGate deny = (t, tier, d) -> PermissionOutcome.DENIED;
        Tool authed = new AuthorizingTool(tool("power", ran), RiskTier.DESTRUCTIVE,
                new PermissionPolicy(), deny, log);

        ToolResult result = authed.execute(new ToolCall("power", Map.of("action", "shutdown")));
        assertFalse(result.success());
        assertFalse(ran.get());                                  // the tool never ran
        assertTrue(result.error().contains("denied"));
        assertTrue(log.recent(1).get(0).event().detail().contains("DENIED"));
    }

    @Test
    void timedOutRequestFailsClosed() {
        AuditLog log = new RecordStoreAuditLog(new InMemoryRecordStore());
        AtomicBoolean ran = new AtomicBoolean();
        PermissionGate timeout = (t, tier, d) -> PermissionOutcome.TIMED_OUT;
        Tool authed = new AuthorizingTool(tool("email_trash", ran), RiskTier.DESTRUCTIVE,
                new PermissionPolicy(), timeout, log);

        assertFalse(authed.execute(new ToolCall("email_trash", Map.of())).success());
        assertFalse(ran.get());
    }
}
