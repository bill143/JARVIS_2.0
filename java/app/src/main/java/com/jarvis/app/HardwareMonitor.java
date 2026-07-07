package com.jarvis.app;

import com.jarvis.integrations.mark.HardwareTool;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Continuous background telemetry: samples CPU and RAM on a fixed interval and raises an alert when
 * a threshold is breached, with hysteresis so a sustained high state alerts once rather than every
 * tick. Alerts are drained by the dashboard via {@code GET /alerts} and spoken/shown there.
 */
public final class HardwareMonitor {

    private volatile double cpuThreshold = 90.0;
    private volatile double ramThreshold = 90.0;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jarvis-hw-monitor");
                t.setDaemon(true);
                return t;
            });
    private final Deque<String> pending = new ArrayDeque<>();
    private boolean cpuHigh = false;
    private boolean ramHigh = false;

    /** Starts sampling every {@code periodSeconds}. */
    public void start(long periodSeconds) {
        scheduler.scheduleAtFixedRate(this::tick, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    /** Adjusts the alert thresholds (percent). Values are clamped to a sane 1–100 range. */
    public void setThresholds(double cpuPercent, double ramPercent) {
        this.cpuThreshold = Math.max(1, Math.min(100, cpuPercent));
        this.ramThreshold = Math.max(1, Math.min(100, ramPercent));
    }

    public double cpuThreshold() {
        return cpuThreshold;
    }

    public double ramThreshold() {
        return ramThreshold;
    }

    private void tick() {
        try {
            evaluate(HardwareTool.sample());
        } catch (RuntimeException e) {
            // Never let a bad sample kill the scheduler.
        }
    }

    /** Applies threshold + hysteresis logic to a sample; package-visible for tests. */
    synchronized void evaluate(HardwareTool.Sample s) {
        if (s.cpuPercent() >= cpuThreshold && !cpuHigh) {
            cpuHigh = true;
            add(String.format("CPU load is high, sir - %.0f%%.", s.cpuPercent()));
        } else if (s.cpuPercent() < cpuThreshold - 10) {
            cpuHigh = false;
        }
        if (s.ramPercent() >= ramThreshold && !ramHigh) {
            ramHigh = true;
            add(String.format("Memory usage is high, sir - %.0f%%.", s.ramPercent()));
        } else if (s.ramPercent() < ramThreshold - 10) {
            ramHigh = false;
        }
    }

    private void add(String alert) {
        pending.addLast(alert);
        while (pending.size() > 10) {
            pending.removeFirst();
        }
    }

    /** Removes and returns all pending alerts. */
    public synchronized List<String> drainAlerts() {
        List<String> out = List.copyOf(pending);
        pending.clear();
        return out;
    }
}
