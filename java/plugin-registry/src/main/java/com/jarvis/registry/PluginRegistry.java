package com.jarvis.registry;

import com.jarvis.tools.RiskTier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The manifest-driven tool registry: the governance layer's view of every tool. It answers two
 * questions the rest of Phase 1 needs — <em>how dangerous is this tool?</em> (from its manifest's
 * {@link RiskTier}, used by the permission layer and audit log) and <em>is it healthy?</em> (from a
 * running success/failure tally, surfaced as {@link ToolHealth}).
 *
 * <p>Thread-safe: health counters are updated from the tool-execution threads while the UI reads
 * snapshots concurrently.
 */
public final class PluginRegistry {

    /** Consecutive failures at which a tool is reported {@link ToolHealth#CIRCUIT_OPEN}. */
    public static final int DEFAULT_CIRCUIT_THRESHOLD = 3;

    private final Map<String, ToolManifest> manifests = new TreeMap<>();
    private final ConcurrentMap<String, Counters> counters = new ConcurrentHashMap<>();
    private final int circuitThreshold;

    public PluginRegistry(Collection<ToolManifest> manifests) {
        this(manifests, DEFAULT_CIRCUIT_THRESHOLD);
    }

    public PluginRegistry(Collection<ToolManifest> manifests, int circuitThreshold) {
        Objects.requireNonNull(manifests, "manifests");
        if (circuitThreshold < 1) {
            throw new IllegalArgumentException("circuitThreshold must be >= 1, got " + circuitThreshold);
        }
        for (ToolManifest m : manifests) {
            this.manifests.put(m.name(), m);   // last one wins on duplicate names
        }
        this.circuitThreshold = circuitThreshold;
    }

    /** The manifest for {@code toolName}, if one was declared. */
    public Optional<ToolManifest> manifestFor(String toolName) {
        return Optional.ofNullable(manifests.get(toolName));
    }

    /** The declared risk tier for {@code toolName}, or {@link RiskTier#UNKNOWN} if unmanifested. */
    public RiskTier riskTier(String toolName) {
        ToolManifest m = manifests.get(toolName);
        return m == null ? RiskTier.UNKNOWN : m.riskTier();
    }

    /** Records a successful invocation, clearing the consecutive-failure streak. */
    public void recordSuccess(String toolName) {
        Counters c = counters.computeIfAbsent(toolName, k -> new Counters());
        c.total.incrementAndGet();
        c.consecutive.set(0);
        c.lastError = "";
    }

    /** Records a failed invocation, extending the consecutive-failure streak. */
    public void recordFailure(String toolName, String error) {
        Counters c = counters.computeIfAbsent(toolName, k -> new Counters());
        c.total.incrementAndGet();
        c.failures.incrementAndGet();
        c.consecutive.incrementAndGet();
        c.lastError = error == null ? "" : error;
    }

    /** Current health of {@code toolName} (OPERATIONAL if it has never been called). */
    public ToolHealth health(String toolName) {
        Counters c = counters.get(toolName);
        if (c == null) {
            return ToolHealth.OPERATIONAL;
        }
        int consecutive = c.consecutive.get();
        if (consecutive >= circuitThreshold) {
            return ToolHealth.CIRCUIT_OPEN;
        }
        return consecutive > 0 ? ToolHealth.DEGRADED : ToolHealth.OPERATIONAL;
    }

    /** A snapshot of {@code toolName}'s risk tier + health metrics. */
    public ToolStats stats(String toolName) {
        Counters c = counters.get(toolName);
        long total = c == null ? 0 : c.total.get();
        long failures = c == null ? 0 : c.failures.get();
        int consecutive = c == null ? 0 : c.consecutive.get();
        String lastError = c == null ? "" : c.lastError;
        return new ToolStats(toolName, riskTier(toolName), health(toolName),
                total, failures, consecutive, lastError);
    }

    /** Snapshots for every known tool (manifested or merely observed), sorted by name. */
    public List<ToolStats> allStats() {
        var names = new java.util.TreeSet<String>(manifests.keySet());
        names.addAll(counters.keySet());
        List<ToolStats> out = new ArrayList<>(names.size());
        for (String name : names) {
            out.add(stats(name));
        }
        return out;
    }

    private static final class Counters {
        final AtomicLong total = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        final AtomicInteger consecutive = new AtomicInteger();
        volatile String lastError = "";
    }
}
