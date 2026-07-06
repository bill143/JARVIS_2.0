package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.integrations.mark.HardwareTool;
import java.util.List;
import org.junit.jupiter.api.Test;

class HardwareMonitorTest {

    private static HardwareTool.Sample cpu(double pct) {
        return new HardwareTool.Sample(pct, 10, 1, 16, 8);
    }

    @Test
    void alertsOnceWhileHighThenAgainAfterRecovery() {
        HardwareMonitor monitor = new HardwareMonitor();

        monitor.evaluate(cpu(95));                // breach -> alert
        monitor.evaluate(cpu(93));                // still high -> no repeat
        List<String> first = monitor.drainAlerts();
        assertEquals(1, first.size());
        assertTrue(first.get(0).contains("CPU"));

        monitor.evaluate(cpu(50));                // recovered (hysteresis)
        monitor.evaluate(cpu(96));                // breach again -> new alert
        assertEquals(1, monitor.drainAlerts().size());
    }

    @Test
    void quietWhenEverythingIsNormal() {
        HardwareMonitor monitor = new HardwareMonitor();
        monitor.evaluate(cpu(20));
        assertTrue(monitor.drainAlerts().isEmpty());
    }

    @Test
    void drainClearsPendingAlerts() {
        HardwareMonitor monitor = new HardwareMonitor();
        monitor.evaluate(new HardwareTool.Sample(10, 95, 15, 16, 8));   // RAM high
        assertEquals(1, monitor.drainAlerts().size());
        assertTrue(monitor.drainAlerts().isEmpty());
    }
}
