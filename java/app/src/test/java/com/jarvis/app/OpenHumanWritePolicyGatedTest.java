package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.audit.AuditedTool;
import com.jarvis.audit.RecordStoreAuditLog;
import com.jarvis.integrations.PluginManager;
import com.jarvis.integrations.openhuman.MemoryWritePolicy;
import com.jarvis.integrations.openhuman.OpenHumanClient;
import com.jarvis.integrations.openhuman.OpenHumanPlugin;
import com.jarvis.integrations.openhuman.OpenHumanResponse;
import com.jarvis.integrations.openhuman.OpenHumanTransport;
import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.registry.HealthTrackingTool;
import com.jarvis.registry.PluginRegistry;
import com.jarvis.security.AuthorizingTool;
import com.jarvis.security.PermissionGate;
import com.jarvis.security.PermissionLevel;
import com.jarvis.security.PermissionOutcome;
import com.jarvis.security.PermissionPolicy;
import com.jarvis.tools.RiskTier;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolRegistry;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * RFC 0001 Phase 1: proves the durable write path enforces BOTH gates end-to-end —
 * (1) the {@link MemoryWritePolicy} role policy (deny-by-default; conductor/authorized only), and
 * (2) the risk-tier permission gate (user confirmation) — and that authorized writes are audited.
 */
class OpenHumanWritePolicyGatedTest {

    private static final class CountingTransport implements OpenHumanTransport {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public OpenHumanResponse send(String method, String path, String jsonBody) {
            calls.incrementAndGet();
            return new OpenHumanResponse(200, "{\"result\":{\"text\":\"stored\"}}");
        }
    }

    private final PluginRegistry registry = AppWiring.pluginRegistry();
    private final AuditLog audit = new RecordStoreAuditLog(new InMemoryRecordStore());

    private Tool govern(Tool raw, PermissionPolicy policy, PermissionGate gate) {
        RiskTier tier = registry.riskTier(raw.name());
        Tool tracked = new HealthTrackingTool(raw, registry);
        Tool audited = new AuditedTool(tracked, audit, tier, AuditTrigger.USER);
        return new AuthorizingTool(audited, tier, policy, gate, audit);
    }

    private Tool writeTool(CountingTransport t, MemoryWritePolicy policy,
            PermissionPolicy perm, PermissionGate gate) {
        ToolRegistry tools = new ToolRegistry();
        new PluginManager(tools).install(new OpenHumanPlugin(new OpenHumanClient(t), policy));
        return govern(tools.lookup("openhuman_memory_write").orElseThrow(), perm, gate);
    }

    private static ToolCall write(String role) {
        return new ToolCall("openhuman_memory_write",
                Map.of("role", role, "content", "60V pack chosen"));
    }

    @Test
    void writeIsTieredMutating() {
        assertEquals(RiskTier.MUTATING, registry.riskTier("openhuman_memory_write"));
    }

    @Test
    void denyByDefaultRefusesEvenTheConductorAndNeverHitsTheNetwork() {
        CountingTransport t = new CountingTransport();
        // Deny-all policy, but the USER gate approves — proving the role policy is a separate gate.
        Tool tool = writeTool(t, MemoryWritePolicy.denyAll(),
                new PermissionPolicy(PermissionLevel.MUTATING),
                (name, tier, detail) -> PermissionOutcome.ALLOWED);

        var r = tool.execute(write("conductor"));

        assertFalse(r.success());
        assertTrue(r.error().contains("deny-by-default"));
        assertEquals(0, t.calls.get());   // refused before any network call
    }

    @Test
    void anUnauthorizedRoleIsRefusedEvenWhenWritesAreEnabled() {
        CountingTransport t = new CountingTransport();
        Tool tool = writeTool(t, new MemoryWritePolicy(true, Set.of("planner")),
                new PermissionPolicy(PermissionLevel.MUTATING),
                (name, tier, detail) -> PermissionOutcome.ALLOWED);

        var r = tool.execute(write("advisor"));   // advisor is never authorized

        assertFalse(r.success());
        assertTrue(r.error().contains("not authorized"));
        assertEquals(0, t.calls.get());
    }

    @Test
    void theUserPermissionGateStillBlocksAnAuthorizedWrite() {
        CountingTransport t = new CountingTransport();
        AtomicInteger prompts = new AtomicInteger();
        Tool tool = writeTool(t, new MemoryWritePolicy(true, Set.of()),
                new PermissionPolicy(PermissionLevel.MUTATING),
                (name, tier, detail) -> {
                    prompts.incrementAndGet();
                    return PermissionOutcome.DENIED;
                });

        var r = tool.execute(write("conductor"));   // role-authorized, but user denies

        assertFalse(r.success());
        assertEquals(1, prompts.get());
        assertEquals(0, t.calls.get());   // permission gate blocked the write
    }

    @Test
    void anAuthorizedConfirmedWriteExecutesAndIsAudited() {
        CountingTransport t = new CountingTransport();
        Tool tool = writeTool(t, new MemoryWritePolicy(true, Set.of()),
                new PermissionPolicy(PermissionLevel.MUTATING),
                (name, tier, detail) -> PermissionOutcome.ALLOWED);

        var r = tool.execute(write("conductor"));

        assertTrue(r.success());
        assertTrue(r.output().contains("stored"));
        assertEquals(1, t.calls.get());   // the write reached the (fake) core
        // Audited: the invocation is recorded in the audit log.
        assertTrue(audit.recent(20).stream()
                .anyMatch(e -> e.event().action().equals("openhuman_memory_write")));
    }
}
