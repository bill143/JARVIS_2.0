package com.jarvis.security;

import com.jarvis.tools.RiskTier;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The production {@link PermissionGate}: bridges a tool thread that needs approval with the browser
 * that grants it. {@link #request} parks the calling thread and publishes a {@link PendingPermission}
 * the dashboard polls; when the user answers, {@link #decide} wakes the thread. If no answer arrives
 * within the timeout the request is denied (fail-closed) so the agent loop never hangs forever.
 */
public final class PermissionBroker implements PermissionGate {

    /** Default time to wait for the user before failing closed. */
    public static final long DEFAULT_TIMEOUT_MILLIS = 120_000;

    private final long timeoutMillis;
    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentMap<String, Waiter> waiting = new ConcurrentHashMap<>();

    public PermissionBroker() {
        this(DEFAULT_TIMEOUT_MILLIS);
    }

    public PermissionBroker(long timeoutMillis) {
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("timeoutMillis must be >= 1, got " + timeoutMillis);
        }
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public PermissionOutcome request(String tool, RiskTier tier, String detail) {
        Objects.requireNonNull(tool, "tool");
        String id = "perm-" + ids.incrementAndGet();
        Waiter waiter = new Waiter(
                new PendingPermission(id, tool, tier, detail, System.currentTimeMillis()));
        waiting.put(id, waiter);
        try {
            if (!waiter.latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                return PermissionOutcome.TIMED_OUT;
            }
            return waiter.allowed ? PermissionOutcome.ALLOWED : PermissionOutcome.DENIED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PermissionOutcome.DENIED;
        } finally {
            waiting.remove(id);
        }
    }

    /**
     * Answers the request {@code id}. Returns {@code true} if a matching pending request existed
     * (an unknown or already-answered id returns {@code false}).
     */
    public boolean decide(String id, boolean allow) {
        Waiter waiter = waiting.get(id);
        if (waiter == null) {
            return false;
        }
        waiter.allowed = allow;
        waiter.latch.countDown();
        return true;
    }

    /** The requests currently awaiting an answer, oldest first. */
    public List<PendingPermission> pending() {
        return waiting.values().stream()
                .map(w -> w.request)
                .sorted(java.util.Comparator.comparingLong(PendingPermission::requestedAtMillis)
                        .thenComparing(PendingPermission::id))
                .toList();
    }

    private static final class Waiter {
        final PendingPermission request;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean allowed;

        Waiter(PendingPermission request) {
            this.request = request;
        }
    }
}
