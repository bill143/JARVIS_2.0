package com.jarvis.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.memory.MemoryEntry;
import com.jarvis.memory.MemoryStore;
import com.jarvis.tools.RiskTier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Standing agents — named, persistent AI agents that live inside JARVIS.
 *
 * <p>Each agent is a saved definition (name, role, bound model provider, standing brief, optional
 * run interval). Agents persist in the {@link MemoryStore} (scope {@code standing_agents}) so they
 * survive restarts, run on demand from the Agents page, and — when given an interval — run
 * autonomously on a background scheduler. Every run is an LLM consult only (READ_ONLY: no tools,
 * no side effects) and is written to the audit log.
 *
 * <p>The actual model call is a {@link Runner} seam injected by {@link WebServer}: agents bound to
 * a configured provider run through the orchestration layer; unbound agents fall back to the
 * active chat brain. Tests inject a fake runner and a fake clock.
 */
final class AgentRegistryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCOPE = "standing_agents";
    private static final int MAX_OUTPUT_CHARS = 6000;

    /** A saved agent definition. {@code intervalMinutes} 0 = on-demand only. */
    record AgentSpec(String id, String name, String role, String provider, String brief,
            int intervalMinutes, boolean enabled) {
    }

    /** A live snapshot of an agent's last run. */
    record StateView(String status, long lastRunAt, long lastLatencyMs, boolean lastOk,
            String lastOutput, String lastError, int totalRuns) {
    }

    /** Executes one agent turn — the model-call seam (fake in tests). */
    @FunctionalInterface
    interface Runner {
        String run(AgentSpec agent, String system, String prompt) throws Exception;
    }

    private static final class RunState {
        volatile long lastRunAt;
        volatile long lastLatencyMs;
        volatile boolean lastOk = true;
        volatile String lastOutput = "";
        volatile String lastError = "";
        final AtomicInteger totalRuns = new AtomicInteger();
        final AtomicBoolean running = new AtomicBoolean();
    }

    private final MemoryStore<String> store;
    private final AuditLog audit;                       // nullable
    private final Runner runner;
    private final LongSupplier clock;
    private final Map<String, RunState> state = new ConcurrentHashMap<>();
    private volatile Thread scheduler;

    AgentRegistryService(MemoryStore<String> store, AuditLog audit, Runner runner) {
        this(store, audit, runner, System::currentTimeMillis);
    }

    AgentRegistryService(MemoryStore<String> store, AuditLog audit, Runner runner,
            LongSupplier clock) {
        this.store = store;
        this.audit = audit;
        this.runner = runner;
        this.clock = clock;
    }

    // ---- definitions -------------------------------------------------------

    /** All saved agents, oldest first (stable order for the UI). */
    List<AgentSpec> list() {
        List<AgentSpec> out = new ArrayList<>();
        for (MemoryEntry<String> e : store.query(SCOPE)) {
            AgentSpec spec = parse(e.key(), e.value());
            if (spec != null) {
                out.add(spec);
            }
        }
        out.sort(Comparator.comparing(AgentSpec::id));
        return out;
    }

    /** One saved agent, if it exists. */
    Optional<AgentSpec> get(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return store.get(SCOPE, id).map(e -> parse(id, e.value()));
    }

    /**
     * Creates or updates an agent. A blank {@code id} creates a new one. Returns the saved spec,
     * or empty when the definition is invalid (blank name).
     */
    Optional<AgentSpec> save(String id, String name, String role, String provider, String brief,
            int intervalMinutes, boolean enabled) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = id == null || id.isBlank()
                ? "ag-" + Long.toHexString(clock.getAsLong()) + "-"
                        + Integer.toHexString(name.hashCode() & 0xffff)
                : id;
        AgentSpec spec = new AgentSpec(key, name.strip(), safe(role), safe(provider), safe(brief),
                Math.max(0, intervalMinutes), enabled);
        ObjectNode o = MAPPER.createObjectNode();
        o.put("name", spec.name());
        o.put("role", spec.role());
        o.put("provider", spec.provider());
        o.put("brief", spec.brief());
        o.put("intervalMinutes", spec.intervalMinutes());
        o.put("enabled", spec.enabled());
        store.put(SCOPE, key, o.toString());
        record("agent_registry", "saved standing agent '" + spec.name() + "'",
                AuditOutcome.SUCCESS);
        return Optional.of(spec);
    }

    /** Deletes an agent definition (its run history goes with it). */
    boolean delete(String id) {
        boolean removed = id != null && !id.isBlank() && store.delete(SCOPE, id);
        if (removed) {
            state.remove(id);
            record("agent_registry", "deleted standing agent " + id, AuditOutcome.SUCCESS);
        }
        return removed;
    }

    /** Flips an agent's enabled flag; returns the updated spec if it exists. */
    Optional<AgentSpec> toggle(String id) {
        return get(id).flatMap(a -> save(a.id(), a.name(), a.role(), a.provider(), a.brief(),
                a.intervalMinutes(), !a.enabled()));
    }

    // ---- execution ---------------------------------------------------------

    /** Live state for an agent (a fresh idle view when it has never run). */
    StateView stateOf(String id) {
        RunState s = state.get(id);
        if (s == null) {
            return new StateView("idle", 0, 0, true, "", "", 0);
        }
        String status = s.running.get() ? "running" : (s.lastError.isBlank() ? "idle" : "error");
        return new StateView(status, s.lastRunAt, s.lastLatencyMs, s.lastOk, s.lastOutput,
                s.lastError, s.totalRuns.get());
    }

    /**
     * Runs one agent turn synchronously: {@code input} when given, else the standing brief.
     * Returns the updated state, or empty when the agent is unknown or already mid-run.
     */
    Optional<StateView> runOnce(String id, String input) {
        Optional<AgentSpec> found = get(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AgentSpec agent = found.get();
        RunState s = state.computeIfAbsent(agent.id(), k -> new RunState());
        if (!s.running.compareAndSet(false, true)) {
            return Optional.empty();   // already mid-run — never overlap an agent with itself
        }
        try {
            String system = systemPrompt(agent);
            String prompt = input == null || input.isBlank()
                    ? "Carry out your standing brief now. Report your findings directly "
                            + "and concisely."
                    : input.strip();
            long t0 = System.nanoTime();
            try {
                String out = runner.run(agent, system, prompt);
                s.lastOutput = truncate(out == null ? "" : out.strip());
                s.lastError = "";
                s.lastOk = true;
                record("agent_registry", "ran '" + agent.name() + "'", AuditOutcome.SUCCESS);
            } catch (Exception e) {
                s.lastOutput = "";
                s.lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                s.lastOk = false;
                record("agent_registry", "'" + agent.name() + "' failed: " + s.lastError,
                        AuditOutcome.FAILURE);
            }
            s.lastLatencyMs = (System.nanoTime() - t0) / 1_000_000;
            s.lastRunAt = clock.getAsLong();
            s.totalRuns.incrementAndGet();
        } finally {
            s.running.set(false);
        }
        return Optional.of(stateOf(agent.id()));
    }

    /**
     * Runs every enabled interval agent that is due at {@code now} (sequentially — standing runs
     * are background work, not a latency path). Returns how many ran.
     */
    int tick(long now) {
        int ran = 0;
        for (AgentSpec a : list()) {
            if (!a.enabled() || a.intervalMinutes() <= 0) {
                continue;
            }
            RunState s = state.get(a.id());
            long last = s == null ? 0 : s.lastRunAt;
            if (last == 0 || now - last >= a.intervalMinutes() * 60_000L) {
                if (runOnce(a.id(), "").isPresent()) {
                    ran++;
                }
            }
        }
        return ran;
    }

    /** Starts the background scheduler (daemon; ticks every {@code tickMillis}). Idempotent. */
    synchronized void startScheduler(long tickMillis) {
        if (scheduler != null && scheduler.isAlive()) {
            return;
        }
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(tickMillis);
                    tick(clock.getAsLong());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // a bad tick must never kill the scheduler
                }
            }
        }, "jarvis-standing-agents");
        t.setDaemon(true);
        t.start();
        scheduler = t;
    }

    /** Stops the background scheduler. */
    synchronized void stopScheduler() {
        if (scheduler != null) {
            scheduler.interrupt();
            scheduler = null;
        }
    }

    // ---- helpers -----------------------------------------------------------

    /** The system brief every run of this agent carries. */
    static String systemPrompt(AgentSpec agent) {
        StringBuilder b = new StringBuilder("You are ").append(agent.name())
                .append(", a standing agent inside J.A.R.V.I.S.");
        if (!agent.role().isBlank()) {
            b.append(" Your role: ").append(agent.role()).append('.');
        }
        if (!agent.brief().isBlank()) {
            b.append("\nStanding brief:\n").append(agent.brief());
        }
        b.append("\nAnswer directly and concisely. You are consulted for text only — you cannot "
                + "run tools or take actions.");
        return b.toString();
    }

    private AgentSpec parse(String id, String json) {
        try {
            JsonNode c = MAPPER.readTree(json);
            return new AgentSpec(id, c.path("name").asText(""), c.path("role").asText(""),
                    c.path("provider").asText(""), c.path("brief").asText(""),
                    c.path("intervalMinutes").asInt(0), c.path("enabled").asBoolean(false));
        } catch (Exception e) {
            return null;   // an unreadable row is skipped, never fatal
        }
    }

    private static String truncate(String s) {
        return s.length() <= MAX_OUTPUT_CHARS ? s : s.substring(0, MAX_OUTPUT_CHARS) + "\n… (truncated)";
    }

    private static String safe(String s) {
        return s == null ? "" : s.strip();
    }

    private void record(String action, String detail, AuditOutcome outcome) {
        if (audit == null) {
            return;
        }
        audit.record(new AuditEvent(AuditCategory.TOOL_INVOCATION, action, AuditTrigger.SYSTEM,
                RiskTier.READ_ONLY, outcome, detail));
    }
}
