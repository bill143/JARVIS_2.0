package com.jarvis.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.tools.RiskTier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class PermissionBrokerTest {

    /** Spins until the broker has a pending request (bounded), then returns its id. */
    private static String awaitPending(PermissionBroker broker) throws InterruptedException {
        for (int i = 0; i < 200 && broker.pending().isEmpty(); i++) {
            Thread.sleep(5);
        }
        assertFalse(broker.pending().isEmpty(), "a request should be pending");
        return broker.pending().get(0).id();
    }

    @Test
    @Timeout(5)
    void approvalUnblocksTheRequestingThread() throws Exception {
        PermissionBroker broker = new PermissionBroker(2_000);
        CompletableFuture<PermissionOutcome> outcome = CompletableFuture.supplyAsync(
                () -> broker.request("email_send", RiskTier.DESTRUCTIVE, "args: [to]"));

        String id = awaitPending(broker);
        assertTrue(broker.decide(id, true));
        assertEquals(PermissionOutcome.ALLOWED, outcome.get(3, TimeUnit.SECONDS));
        assertTrue(broker.pending().isEmpty());   // cleared after answering
    }

    @Test
    @Timeout(5)
    void denialIsReportedAsDenied() throws Exception {
        PermissionBroker broker = new PermissionBroker(2_000);
        CompletableFuture<PermissionOutcome> outcome = CompletableFuture.supplyAsync(
                () -> broker.request("power", RiskTier.DESTRUCTIVE, "args: [action]"));
        broker.decide(awaitPending(broker), false);
        assertEquals(PermissionOutcome.DENIED, outcome.get(3, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(5)
    void noAnswerWithinTimeoutFailsClosed() {
        PermissionBroker broker = new PermissionBroker(40);   // 40ms
        assertEquals(PermissionOutcome.TIMED_OUT,
                broker.request("email_trash", RiskTier.DESTRUCTIVE, "args: [id]"));
    }

    @Test
    void decidingAnUnknownRequestReturnsFalse() {
        assertFalse(new PermissionBroker().decide("perm-999", true));
    }
}
