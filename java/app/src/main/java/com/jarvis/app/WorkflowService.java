package com.jarvis.app;

import com.jarvis.api.ChatRequest;
import com.jarvis.api.JarvisApi;
import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.memory.RecordStore;
import com.jarvis.tools.RiskTier;
import com.jarvis.workflows.TriggerType;
import com.jarvis.workflows.Workflow;
import com.jarvis.workflows.WorkflowRun;
import com.jarvis.workflows.WorkflowRunStore;
import com.jarvis.workflows.WorkflowRunner;
import com.jarvis.workflows.WorkflowScheduler;
import com.jarvis.workflows.WorkflowStore;
import java.util.List;

/**
 * App-level facade over the workflows module: it runs each step through the governed agent
 * ({@link JarvisApi#chat}, which enforces the Phase 1 permission + audit gate and per-turn tool
 * budget), records every run to history, audits it (AUTONOMOUS for scheduled runs), and manages the
 * scheduler. The {@link WorkflowRunner}'s MAX_STEPS cap is the workflow-level budget guard.
 */
final class WorkflowService {

    private final WorkflowStore defs;
    private final WorkflowRunStore runs;
    private final WorkflowRunner runner;
    private final WorkflowScheduler scheduler = new WorkflowScheduler();
    private final JarvisApi api;
    private final AuditLog audit;   // nullable

    WorkflowService(RecordStore defStore, RecordStore runStore, JarvisApi api, AuditLog audit) {
        this.defs = new WorkflowStore(defStore);
        this.runs = new WorkflowRunStore(runStore);
        this.runner = new WorkflowRunner(runs);
        this.api = api;
        this.audit = audit;
    }

    List<Workflow> list() {
        return defs.list();
    }

    Workflow save(Workflow workflow) {
        Workflow saved = defs.save(workflow);
        reschedule();
        return saved;
    }

    void delete(String id) {
        defs.delete(id);
        scheduler.cancel(id);
    }

    List<WorkflowRun> recentRuns(int max) {
        return runs.recent(max);
    }

    /** Runs a workflow now under {@code trigger}; returns the run, or null if unknown. */
    WorkflowRun run(String id, TriggerType trigger) {
        Workflow wf = defs.get(id).orElse(null);
        if (wf == null) {
            return null;
        }
        Workflow effective = new Workflow(wf.id(), wf.name(), wf.steps(), trigger, wf.intervalSeconds());
        WorkflowRun run = runner.run(effective,
                step -> api.chat(new ChatRequest("workflow", step)).response(),
                System.currentTimeMillis());
        if (audit != null) {
            audit.record(new AuditEvent(AuditCategory.SYSTEM, "workflow:" + wf.name(),
                    trigger == TriggerType.SCHEDULE ? AuditTrigger.AUTONOMOUS : AuditTrigger.USER,
                    RiskTier.UNKNOWN, run.ok() ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE,
                    "ran " + run.steps().size() + " step(s)"));
        }
        return run;
    }

    /** Schedules all SCHEDULE workflows (called at startup and after edits). */
    void startScheduler() {
        reschedule();
    }

    private void reschedule() {
        for (Workflow wf : defs.list()) {
            if (wf.trigger() == TriggerType.SCHEDULE) {
                scheduler.schedule(wf, () -> run(wf.id(), TriggerType.SCHEDULE));
            } else {
                scheduler.cancel(wf.id());
            }
        }
    }
}
