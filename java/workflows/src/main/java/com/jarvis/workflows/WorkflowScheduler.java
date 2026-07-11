package com.jarvis.workflows;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs SCHEDULE-triggered workflows on a JDK {@link ScheduledExecutorService} (daemon thread, so it
 * never blocks shutdown). Intervals are clamped to at least {@link #MIN_INTERVAL_SECONDS} — a guard
 * against a workflow scheduled to hammer the agent. MANUAL/WEBHOOK workflows are ignored here.
 */
public final class WorkflowScheduler {

    /** Minimum allowed schedule interval. */
    public static final long MIN_INTERVAL_SECONDS = 10;

    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "jarvis-workflow");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, ScheduledFuture<?>> jobs = new ConcurrentHashMap<>();

    /** Schedules {@code workflow} if it is a SCHEDULE trigger; otherwise does nothing. */
    public void schedule(Workflow workflow, Runnable task) {
        Objects.requireNonNull(workflow, "workflow");
        if (workflow.trigger() != TriggerType.SCHEDULE) {
            return;
        }
        long millis = Math.max(MIN_INTERVAL_SECONDS, workflow.intervalSeconds()) * 1000;
        scheduleEvery(workflow.id(), task, millis);
    }

    /** Package-visible: schedule an arbitrary interval (used by tests and by {@link #schedule}). */
    void scheduleEvery(String id, Runnable task, long intervalMillis) {
        cancel(id);
        jobs.put(id, exec.scheduleAtFixedRate(() -> {
            try {
                task.run();
            } catch (RuntimeException ignored) {
                // a failing scheduled run must not kill the scheduler
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS));
    }

    public void cancel(String id) {
        ScheduledFuture<?> f = jobs.remove(id);
        if (f != null) {
            f.cancel(false);
        }
    }

    public int scheduledCount() {
        return jobs.size();
    }

    public void shutdown() {
        exec.shutdownNow();
        jobs.clear();
    }
}
