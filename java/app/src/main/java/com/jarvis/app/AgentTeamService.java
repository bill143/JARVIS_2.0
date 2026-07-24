package com.jarvis.app;

import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.tools.RiskTier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * A dynamic, specialized, self-correcting agent team — the re-architecture of the rigid
 * PLANNER→EXECUTOR→CRITIC chain.
 *
 * <ul>
 *   <li><b>Dynamic composition</b>: {@link #compose} selects only the roles a task needs.</li>
 *   <li><b>Specialized roles</b>: each {@link RoleSpec} carries its own system prompt, model and
 *       token budget rather than forcing one model to play every part.</li>
 *   <li><b>Parallel execution</b>: independent sub-tasks fan out over JDK virtual threads.</li>
 *   <li><b>Shared scratchpad</b>: a thread-safe blackboard every role reads and writes, optionally
 *       grounded with Brain/Obsidian snippets.</li>
 *   <li><b>Self-correction</b>: a failing Critic routes structured feedback back to the Executor for a
 *       bounded number of retries.</li>
 *   <li><b>Budget tracking</b>: tokens, estimated cost and elapsed time are tracked; the run degrades
 *       (stops retrying) as it nears a limit and pauses if it exceeds one.</li>
 *   <li><b>Human-in-the-loop</b>: a run can be paused and redirected mid-flight; injected feedback
 *       enters the scratchpad at the next checkpoint.</li>
 * </ul>
 *
 * <p>All model turns go through a {@link Turn} seam so the engine is fully testable offline.
 */
final class AgentTeamService {

    /** A single model turn: given a role and a prompt, return the text. Seam for tests. */
    @FunctionalInterface
    interface Turn {
        String run(RoleSpec role, String prompt) throws Exception;
    }

    /** A specialized role definition. */
    record RoleSpec(String role, String system, String model, int maxTokens) {
    }

    /** One recorded agent action in a run. */
    record AgentStep(String role, String phase, String output, long latencyMs, boolean ok,
            String error) {
    }

    /** A run's resource ceiling. Any field &le; 0 means "no limit". */
    record Budget(long maxTokens, double maxCostUsd, long maxMillis) {
    }

    /** Live resource usage for a run. */
    record Usage(long tokens, double costUsd, long elapsedMs, boolean nearLimit, boolean exceeded) {
    }

    /** The default specialized roster (each role gets a distinct brief and token budget). */
    static final Map<String, RoleSpec> DEFAULT_ROLES = Map.of(
            "planner", new RoleSpec("planner",
                    "You are the PLANNER. Break the task into a short, ordered list of concrete "
                            + "sub-tasks, one per line. No prose.", "", 512),
            "executor", new RoleSpec("executor",
                    "You are the EXECUTOR. Carry out the work precisely using the plan and scratchpad. "
                            + "Produce the deliverable.", "", 1024),
            "critic", new RoleSpec("critic",
                    "You are the CRITIC. Review the executor's work against the task. Reply with "
                            + "'PASS' if it is correct and complete, or 'FAIL: <specific, actionable "
                            + "feedback>' otherwise.", "", 512));

    static final int MAX_RETRIES = 3;

    private final Turn turn;
    private final AuditLog audit;                       // nullable
    private final java.util.function.Function<String, List<String>> grounding;  // nullable: Brain hook
    private final Map<String, RoleSpec> roles;
    private final Map<String, TeamRun> runs = new ConcurrentHashMap<>();
    private final AtomicLong runSeq = new AtomicLong();

    AgentTeamService(Turn turn, AuditLog audit,
            java.util.function.Function<String, List<String>> grounding) {
        this(turn, audit, grounding, DEFAULT_ROLES);
    }

    AgentTeamService(Turn turn, AuditLog audit,
            java.util.function.Function<String, List<String>> grounding, Map<String, RoleSpec> roles) {
        this.turn = turn;
        this.audit = audit;
        this.grounding = grounding;
        this.roles = roles;
    }

    /**
     * Dynamic team composition: pick only the roles a task needs. A short, direct ask runs the
     * Executor alone; anything asking for a plan/steps adds a Planner; anything asking for
     * correctness/verification/review (or that is long) adds a Critic. Always includes the Executor.
     */
    List<String> compose(String task) {
        String t = task == null ? "" : task.toLowerCase();
        List<String> team = new ArrayList<>();
        boolean wantsPlan = t.contains("plan") || t.contains("steps") || t.contains("step by step")
                || t.contains("break down") || t.contains("strategy") || t.length() > 240;
        boolean wantsCritic = t.contains("verify") || t.contains("check") || t.contains("review")
                || t.contains("correct") || t.contains("accurate") || t.contains("rigorous")
                || t.contains("careful") || t.length() > 160;
        if (wantsPlan) {
            team.add("planner");
        }
        team.add("executor");
        if (wantsCritic) {
            team.add("critic");
        }
        return team;
    }

    /** The result of a completed run (also reachable live via {@link #status}). */
    record TeamResult(String task, List<String> team, List<AgentStep> steps, String answer,
            Map<String, String> scratchpad, Usage usage, boolean approvedByCritic, int retries,
            String status) {
    }

    /** Runs a team synchronously to completion (no HITL). Convenience over {@link #start}. */
    TeamResult run(String task, Budget budget) {
        TeamRun r = new TeamRun(nextId(), task, budget);
        execute(r);
        return r.snapshot();
    }

    /** Starts a run on a background virtual thread and returns its id (for HITL status/redirect). */
    String start(String task, Budget budget) {
        TeamRun r = new TeamRun(nextId(), task, budget);
        runs.put(r.id, r);
        r.worker = Thread.ofVirtual().name("agent-team-" + r.id).start(() -> execute(r));
        return r.id;
    }

    /** A live snapshot of a run, or null if unknown. */
    TeamResult status(String runId) {
        TeamRun r = runs.get(runId);
        return r == null ? null : r.snapshot();
    }

    /** Injects human feedback into a running team; it is honored at the next checkpoint. */
    boolean redirect(String runId, String feedback) {
        TeamRun r = runs.get(runId);
        if (r == null || feedback == null || feedback.isBlank()) {
            return false;
        }
        r.redirects.add(feedback.strip());
        record("agent:HITL", "redirect: " + feedback.strip(), RiskTier.READ_ONLY);
        return true;
    }

    // ---- engine ----

    private void execute(TeamRun r) {
        r.status = "running";
        List<String> team = compose(r.task);
        r.team = team;
        r.scratchpad.put("task", r.task);
        if (grounding != null) {
            List<String> notes = grounding.apply(r.task);
            if (notes != null && !notes.isEmpty()) {
                r.scratchpad.put("grounding", String.join("\n", notes));
            }
        }

        // 1) PLANNER (optional) → subtasks in the scratchpad.
        List<String> subtasks = new ArrayList<>();
        if (team.contains("planner")) {
            String plan = phase(r, roles.get("planner"), "plan", planPrompt(r));
            for (String line : plan.split("\n")) {
                String s = line.replaceFirst("^[-*\\d.\\s]+", "").strip();
                if (!s.isBlank()) {
                    subtasks.add(s);
                }
            }
            r.scratchpad.put("plan", plan);
        }
        if (checkpoint(r)) {
            return;
        }

        // 2) EXECUTOR — independent subtasks fan out over virtual threads; otherwise a single pass.
        String work;
        if (subtasks.size() > 1) {
            work = executeParallel(r, subtasks);
        } else {
            work = phase(r, roles.get("executor"), "work", executorPrompt(r, ""));
        }
        r.scratchpad.put("work", work);
        r.answer = work;

        // 3) CRITIC (optional) with bounded self-correction: review, and on failure route feedback
        // back to the Executor for at most MAX_RETRIES corrective passes.
        if (team.contains("critic")) {
            while (true) {
                if (checkpoint(r)) {
                    return;
                }
                String verdict = phase(r, roles.get("critic"), "critique", criticPrompt(r));
                if (verdict.strip().toUpperCase().startsWith("PASS")) {
                    r.approvedByCritic = true;
                    break;
                }
                if (r.retries >= MAX_RETRIES || r.usage().nearLimit()) {
                    // Degrade gracefully: stop retrying near the budget or after the cap.
                    r.scratchpad.put("degraded", r.usage().nearLimit()
                            ? "stopped retrying near budget limit" : "retry cap reached");
                    break;
                }
                r.retries++;
                String feedback = verdict.replaceFirst("(?i)^FAIL:?\\s*", "").strip();
                r.scratchpad.put("feedback", feedback);
                work = phase(r, roles.get("executor"), "work", executorPrompt(r, feedback));
                r.scratchpad.put("work", work);
                r.answer = work;
            }
        } else {
            r.approvedByCritic = true;   // no critic requested → executor output stands
        }

        r.status = r.usage().exceeded() ? "paused: budget exceeded" : "done";
        record("agent:team", "task complete (" + String.join("+", team) + ", retries=" + r.retries
                + ", tokens=" + r.usage().tokens() + ")", RiskTier.MUTATING);
    }

    /** Runs subtasks concurrently on virtual threads and merges their outputs deterministically. */
    private String executeParallel(TeamRun r, List<String> subtasks) {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = new ArrayList<>();
            for (String sub : subtasks) {
                futures.add(pool.submit(() -> phase(r, roles.get("executor"), "work",
                        executorPrompt(r, "") + "\nFocus only on this sub-task: " + sub)));
            }
            StringBuilder merged = new StringBuilder();
            for (int i = 0; i < futures.size(); i++) {
                String out;
                try {
                    out = futures.get(i).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    out = "(interrupted)";
                } catch (ExecutionException e) {
                    out = "(sub-task failed)";
                }
                merged.append("• ").append(subtasks.get(i)).append(": ").append(out).append('\n');
            }
            return merged.toString().strip();
        }
    }

    /** Runs one role turn, tracking latency + budget and recording a step. Never throws. */
    private String phase(TeamRun r, RoleSpec role, String phaseName, String prompt) {
        long start = System.nanoTime();
        String out;
        boolean ok;
        String err = "";
        try {
            out = turn.run(role, prompt);
            out = out == null ? "" : out;
            ok = true;
        } catch (Exception e) {
            out = "";
            ok = false;
            err = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        r.addUsage(estimateTokens(prompt) + estimateTokens(out), role.model());
        r.steps.add(new AgentStep(role.role(), phaseName, out, ms, ok, err));
        record("agent:" + role.role().toUpperCase(), phaseName + " (" + role.role() + ")",
                riskFor(role.role()));
        return out;
    }

    /** Between phases: apply any queued human redirects and pause if the budget is blown. */
    private boolean checkpoint(TeamRun r) {
        String fb;
        while ((fb = r.redirects.poll()) != null) {
            r.scratchpad.merge("human", fb, (a, b) -> a + "\n" + b);
            r.scratchpad.put("feedback", fb);   // steer the next executor pass
        }
        if (r.usage().exceeded()) {
            r.status = "paused: budget exceeded";
            r.scratchpad.put("degraded", "budget exceeded — run paused");
            return true;
        }
        return false;
    }

    private String planPrompt(TeamRun r) {
        return context(r) + "Task: " + r.task;
    }

    private String executorPrompt(TeamRun r, String feedback) {
        String base = context(r) + "Task: " + r.task;
        String plan = r.scratchpad.get("plan");
        if (plan != null && !plan.isBlank()) {
            base += "\nPlan:\n" + plan;
        }
        String human = r.scratchpad.get("human");
        if (human != null && !human.isBlank()) {
            base += "\nHuman guidance (follow it):\n" + human;
        }
        if (feedback != null && !feedback.isBlank()) {
            base += "\nRevise to address this critic feedback:\n" + feedback;
        }
        return base;
    }

    private String criticPrompt(TeamRun r) {
        return context(r) + "Task: " + r.task + "\nExecutor's work:\n"
                + r.scratchpad.getOrDefault("work", "");
    }

    /** The grounding block shared to every role (Brain snippets), if any. */
    private String context(TeamRun r) {
        String g = r.scratchpad.get("grounding");
        return g == null || g.isBlank() ? "" : ("Grounding notes from your knowledge base:\n" + g
                + "\n\n");
    }

    private static long estimateTokens(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        return Math.round(s.trim().split("\\s+").length * 1.3);   // ~1.3 tokens/word
    }

    /** Rough cost per 1K tokens by model family (USD); unknown → a small default. */
    private static double ratePer1k(String model) {
        String m = model == null ? "" : model.toLowerCase();
        if (m.contains("opus")) {
            return 0.015;
        }
        if (m.contains("sonnet") || m.contains("gpt-4")) {
            return 0.003;
        }
        if (m.contains("haiku") || m.contains("mini") || m.contains("nano")) {
            return 0.0004;
        }
        return 0.001;
    }

    private static RiskTier riskFor(String role) {
        return switch (role) {
            case "executor" -> RiskTier.MUTATING;
            default -> RiskTier.READ_ONLY;   // planner / critic only read & reason
        };
    }

    private String nextId() {
        return "team-" + runSeq.incrementAndGet();
    }

    private void record(String action, String detail, RiskTier tier) {
        if (audit == null) {
            return;
        }
        audit.record(new AuditEvent(AuditCategory.SYSTEM, action, AuditTrigger.AUTONOMOUS, tier,
                AuditOutcome.SUCCESS, detail));
    }

    /** Mutable per-run state. Fields touched across threads are guarded/atomic. */
    private final class TeamRun {
        final String id;
        final String task;
        final Budget budget;
        final long startMillis = System.currentTimeMillis();
        final List<AgentStep> steps = java.util.Collections.synchronizedList(new ArrayList<>());
        final Map<String, String> scratchpad = new ConcurrentHashMap<>();
        final LinkedBlockingQueue<String> redirects = new LinkedBlockingQueue<>();
        final AtomicLong tokens = new AtomicLong();
        final java.util.concurrent.atomic.DoubleAdder cost =
                new java.util.concurrent.atomic.DoubleAdder();
        volatile List<String> team = List.of();
        volatile String answer = "";
        volatile boolean approvedByCritic;
        volatile int retries;
        volatile String status = "pending";
        volatile Thread worker;

        TeamRun(String id, String task, Budget budget) {
            this.id = id;
            this.task = task;
            this.budget = budget == null ? new Budget(0, 0, 0) : budget;
        }

        void addUsage(long addTokens, String model) {
            tokens.addAndGet(addTokens);
            cost.add(addTokens / 1000.0 * ratePer1k(model));
        }

        Usage usage() {
            long tk = tokens.get();
            double cs = cost.sum();
            long el = System.currentTimeMillis() - startMillis;
            boolean over = (budget.maxTokens() > 0 && tk > budget.maxTokens())
                    || (budget.maxCostUsd() > 0 && cs > budget.maxCostUsd())
                    || (budget.maxMillis() > 0 && el > budget.maxMillis());
            boolean near = !over && ((budget.maxTokens() > 0 && tk > budget.maxTokens() * 0.8)
                    || (budget.maxCostUsd() > 0 && cs > budget.maxCostUsd() * 0.8)
                    || (budget.maxMillis() > 0 && el > budget.maxMillis() * 0.8));
            return new Usage(tk, Math.round(cs * 1e6) / 1e6, el, near, over);
        }

        TeamResult snapshot() {
            return new TeamResult(task, team, new ArrayList<>(steps), answer,
                    new LinkedHashMap<>(scratchpad), usage(), approvedByCritic, retries, status);
        }
    }
}
