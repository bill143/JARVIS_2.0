package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.integrations.openhuman.OpenHumanClient;
import com.jarvis.integrations.openhuman.OpenHumanResponse;
import com.jarvis.integrations.openhuman.OpenHumanTransport;
import com.jarvis.memory.InMemoryStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenHumanWriteGateTest {

    private static OpenHumanWriteGate gate() {
        return new OpenHumanWriteGate(new InMemoryStore<>(), null);
    }

    @Test
    void deniesByDefaultWhenNothingIsConfigured() {
        OpenHumanWriteGate g = gate();
        assertFalse(g.permits("kart-notes", "battery-spec"));
    }

    @Test
    void deniesWhenDisabledEvenWithAnAllowlist() {
        OpenHumanWriteGate g = gate();
        g.setConfig(false, List.of("kart-notes"));
        assertFalse(g.permits("kart-notes", "battery-spec"));
    }

    @Test
    void enabledSelfHostedButEmptyAllowlistStillDefaultDenies() {
        OpenHumanWriteGate g = gate();
        g.setConfig(true, List.of());
        assertFalse(g.permits("kart-notes", "battery-spec"));
    }

    @Test
    void approvesWhenEnabledAndAllowlisted() {
        OpenHumanWriteGate g = gate();
        g.setConfig(true, List.of("kart-notes"));
        assertTrue(g.permits("kart-notes", "battery-spec"));
    }

    @Test
    void deniesOutsideTheAllowlist() {
        OpenHumanWriteGate g = gate();
        g.setConfig(true, List.of("kart-notes"));
        assertFalse(g.permits("unrelated-namespace", "battery-spec"));
    }

    @Test
    void harmDenylistAlwaysWinsEvenIfAllowlisted() {
        OpenHumanWriteGate g = gate();
        g.setConfig(true, List.of("weapon"));
        assertFalse(g.permits("weapon-designs", "plans"));
    }

    // ---- integration with OpenHumanClient.memoryWrite: the gate runs before any network call ----

    @Test
    void deniedWritesNeverTouchTheNetwork() {
        AtomicCallCounter calls = new AtomicCallCounter();
        OpenHumanTransport transport = counting(calls, new OpenHumanResponse(200, "{\"result\":{\"document_id\":\"d1\"}}"));
        OpenHumanClient client = new OpenHumanClient(transport, OpenHumanClient.DEFAULT_MEMORY_SEARCH_METHOD,
                OpenHumanClient.DEFAULT_CONSULT_METHOD, OpenHumanClient.DEFAULT_MEMORY_WRITE_METHOD, gate());

        assertThrows(SecurityException.class,
                () -> client.memoryWrite("kart-notes", "battery-spec", "Battery", "60V pack"));
        assertTrue(calls.count == 0);
    }

    @Test
    void approvedWritesReachTheRealRpcShape() throws Exception {
        OpenHumanWriteGate g = gate();
        g.setConfig(true, List.of("kart-notes"));
        AtomicCallCounter calls = new AtomicCallCounter();
        OpenHumanTransport transport = counting(calls, new OpenHumanResponse(200, "{\"result\":{\"document_id\":\"doc-9\"}}"));
        OpenHumanClient client = new OpenHumanClient(transport, OpenHumanClient.DEFAULT_MEMORY_SEARCH_METHOD,
                OpenHumanClient.DEFAULT_CONSULT_METHOD, OpenHumanClient.DEFAULT_MEMORY_WRITE_METHOD, g);

        String docId = client.memoryWrite("kart-notes", "battery-spec", "Battery", "60V pack");

        assertTrue(calls.count == 1);
        assertTrue("doc-9".equals(docId));
    }

    private static final class AtomicCallCounter {
        volatile int count;
    }

    private static OpenHumanTransport counting(AtomicCallCounter counter, OpenHumanResponse response) {
        return (method, path, body) -> {
            counter.count++;
            return response;
        };
    }
}
