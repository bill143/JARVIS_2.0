package com.jarvis.integrations.mark;

import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.lang.management.ManagementFactory;

/**
 * Point-in-time hardware telemetry: CPU load and RAM usage from the platform MX beans. GPU and
 * per-sensor temperature are not exposed by the JDK on Windows without native libraries, so they
 * are honestly reported as unavailable rather than faked. Continuous monitoring with alerts lives
 * in the app's HardwareMonitor; this tool answers "how's my system doing" on demand.
 */
public final class HardwareTool implements Tool {

    /** Reads a fresh telemetry sample. Exposed so the monitor and the tool share one source. */
    public static Sample sample() {
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        double cpu = os.getCpuLoad();
        long totalMem = os.getTotalMemorySize();
        long freeMem = os.getFreeMemorySize();
        double ramPct = totalMem == 0 ? 0 : (100.0 * (totalMem - freeMem) / totalMem);
        return new Sample(cpu < 0 ? 0 : cpu * 100.0, ramPct,
                (totalMem - freeMem) / (1024 * 1024 * 1024.0), totalMem / (1024 * 1024 * 1024.0),
                os.getAvailableProcessors());
    }

    /** One telemetry reading. */
    public record Sample(double cpuPercent, double ramPercent,
            double ramUsedGb, double ramTotalGb, int cores) {
    }

    @Override
    public String name() {
        return "hardware_status";
    }

    @Override
    public String description() {
        return "Current CPU load and RAM usage for this machine. No args.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Sample s = sample();
        return ToolResult.ok(String.format(
                "CPU: %.0f%% across %d cores%nRAM: %.0f%% (%.1f of %.1f GB used)"
                        + "%nGPU/temperature: not available without native sensors",
                s.cpuPercent(), s.cores(), s.ramPercent(), s.ramUsedGb(), s.ramTotalGb()));
    }
}
