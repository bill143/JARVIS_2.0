package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.memory.InMemoryStore;
import com.jarvis.memory.MemoryStore;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AgentRegistryServiceTest {

    private static AgentRegistryService svc(MemoryStore<String> store,
            AgentRegistryService.Runner runner) {
        return new AgentRegistryService(store, null, runner);
    }

    private static AgentRegistryService.AgentSpec saved(AgentRegistryService s, String name,
            int intervalMinutes, boolean enabled) {
        return s.save("", name, "researcher", "", "watch things", intervalMinutes, enabled)
                .orElseThrow();
    }

    // ---- definitions -------------------------------------------------------

    @Test
    void saveListGetRoundtrip() {
        AgentRegistryService s = svc(new InMemoryStore<>(), (a, sys, p) -> "ok");
        AgentRegistryService.AgentSpec a = s.save("", "Watcher", "analyst", "NVIDIA-main",
                "watch the market", 15, true).orElseThrow();
        assertFalse(a.id().isBlank());
        assertEquals("Watcher", a.name());
        assertEquals(15, a.intervalMinutes());
        List<AgentRegistryService.AgentSpec> all = s.list();
        assertEquals(1, all.size());
        assertEquals(a, all.get(0));
        assertEquals(a, s.get(a.id()).orElseThrow());
    }

    @Test
    void blankNameIsRejected() {
        AgentRegistryService s = svc(new InMemoryStore<>(), (a, sys, p) -> "ok");
        assertTrue(s.save("", "  ", "r", "", "b", 0, true).isEmpty());
        assertTrue(s.list().isEmpty());
    }

    @Test
    void savingWithExistingIdUpdatesInPlace() {
        AgentRegistryService s = svc(new InMemoryStore<>(), (a, sys, p) -> "ok");
        AgentRegistryService.AgentSpec a = saved(s, "Watcher", 0, true);
        AgentRegistryService.AgentSpec b = s.save(a.id(), "Watcher 2", "critic", "prov",
                "new brief", 5, false).orElseThrow();
        assertEquals(a.id(), b.id());
        assertEquals(1, s.list().size());
        assertEquals("Watcher 2", s.get(a.id()).orElseThrow().name());
    }

    @Test
    void deleteRemovesTheAgent() {
        AgentRegistryService s = svc(new InMemoryStore<>(), (a, sys, p) -> "ok");
        AgentRegistryService.AgentSpec a = saved(s, "Doomed", 0, true);
        assertTrue(s.delete(a.id()));
        assertFalse(s.delete(a.id()));   // second delete: already gone
        assertTrue(s.list().isEmpty());
    }

    @Test
    void toggleFlipsEnabled() {
        AgentRegistryService s = svc(new InMemoryStore<>(), (a, sys, p) -> "ok");
        AgentRegistryService.AgentSpec a = saved(s, "Flip", 0, true);
        assertFalse(s.toggle(a.id()).orElseThrow().enabled());
        assertTrue(s.toggle(a.id()).orElseThrow().enabled());
        assertTrue(s.toggle("nope").isEmpty());
    }

    @Test
    void definitionsPersistAcrossServiceInstances() {
        MemoryStore<String> store = new InMemoryStore<>();
        AgentRegistryService first = svc(store, (a, sys, p) -> "ok");
        AgentRegistryService.AgentSpec a = saved(first, "Durable", 30, true);
        AgentRegistryService second = svc(store, (a2, sys, p) -> "ok");
        assertEquals("Durable", second.get(a.id()).orElseThrow().name());
        assertEquals(30, second.get(a.id()).orElseThrow().intervalMinutes());
    }

    // ---- execution ---------------------------------------------------------

    @Test
    void runOnceRecordsSuccessState() {
        AgentRegistryService s = svc(new InMemoryStore<>(),
                (a, sys, p) -> "report for " + a.name());
        AgentRegistryService.AgentSpec a = saved(s, "Runner", 0, true);
        AgentRegistryService.StateView v = s.runOnce(a.id(), "").orElseThrow();
        assertTrue(v.lastOk());
        assertEquals("report for Runner", v.lastOutput());
        assertEquals("", v.lastError());
        assertEquals(1, v.totalRuns());
        assertEquals("idle", v.status());
    }

    @Test
    void runOnceRecordsFailureState() {
        AgentRegistryService s = svc(new InMemoryStore<>(), (a, sys, p) -> {
            throw new IOException("model unreachable");
        });
        AgentRegistryService.AgentSpec a = saved(s, "Failing", 0, true);
        AgentRegistryService.StateView v = s.runOnce(a.id(), "").orElseThrow();
        assertFalse(v.lastOk());
        assertEquals("model unreachable", v.lastError());
        assertEquals("error", v.status());
    }

    @Test
    void runOnceUnknownAgentIsEmpty() {
        AgentRegistryService s = svc(new InMemoryStore<>(), (a, sys, p) -> "ok");
        assertTrue(s.runOnce("nope", "").isEmpty());
    }

    @Test
    void runOncePassesSystemAndInputThrough() {
        StringBuilder seen = new StringBuilder();
        AgentRegistryService s = svc(new InMemoryStore<>(), (a, sys, p) -> {
            seen.append(sys).append("|").append(p);
            return "ok";
        });
        AgentRegistryService.AgentSpec a = s.save("", "Scout", "recon", "", "scan the perimeter",
                0, true).orElseThrow();
        s.runOnce(a.id(), "check sector 7");
        assertTrue(seen.toString().contains("You are Scout"));
        assertTrue(seen.toString().contains("scan the perimeter"));
        assertTrue(seen.toString().contains("check sector 7"));
    }

    @Test
    void longOutputIsTruncated() {
        AgentRegistryService s = svc(new InMemoryStore<>(), (a, sys, p) -> "x".repeat(10_000));
        AgentRegistryService.AgentSpec a = saved(s, "Chatty", 0, true);
        String out = s.runOnce(a.id(), "").orElseThrow().lastOutput();
        assertTrue(out.length() < 7000);
        assertTrue(out.endsWith("(truncated)"));
    }

    // ---- scheduler tick ----------------------------------------------------

    @Test
    void tickRunsOnlyDueEnabledIntervalAgents() {
        AtomicLong now = new AtomicLong(1_000_000L);
        AtomicLong runs = new AtomicLong();
        AgentRegistryService s = new AgentRegistryService(new InMemoryStore<>(), null,
                (a, sys, p) -> {
                    runs.incrementAndGet();
                    return "ok";
                }, now::get);
        saved(s, "Every5", 5, true);        // due (never ran)
        saved(s, "Disabled", 5, false);     // never runs: disabled
        saved(s, "OnDemand", 0, true);      // never runs via tick: no interval

        assertEquals(1, s.tick(now.get()));   // only Every5 runs
        assertEquals(1, runs.get());

        now.addAndGet(4 * 60_000L);           // 4 min later — not due yet
        assertEquals(0, s.tick(now.get()));

        now.addAndGet(60_000L);               // 5 min mark — due again
        assertEquals(1, s.tick(now.get()));
        assertEquals(2, runs.get());
    }

    @Test
    void schedulerStartStopIsSafe() {
        AgentRegistryService s = svc(new InMemoryStore<>(), (a, sys, p) -> "ok");
        s.startScheduler(60_000);
        s.startScheduler(60_000);   // idempotent
        s.stopScheduler();
        s.stopScheduler();          // safe twice
    }
}
