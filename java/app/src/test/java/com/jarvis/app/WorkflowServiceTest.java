package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditQuery;
import com.jarvis.audit.RecordStoreAuditLog;
import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.memory.InMemoryStore;
import com.jarvis.workflows.TriggerType;
import com.jarvis.workflows.Workflow;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowServiceTest {

    @Test
    void everyRunIsRecordedToHistoryAndAuditedViaPhase1() {
        AuditLog audit = new RecordStoreAuditLog(new InMemoryRecordStore());
        WorkflowService svc = new WorkflowService(new InMemoryRecordStore(), new InMemoryRecordStore(),
                AppWiring.buildApi(null, "m", new InMemoryStore<>()), audit);
        svc.save(new Workflow("w", "Nightly", List.of("do a thing"), TriggerType.MANUAL, 0));

        svc.run("w", TriggerType.MANUAL);

        // Recorded to run history...
        assertEquals(1, svc.recentRuns(10).size());
        assertEquals("Nightly", svc.recentRuns(10).get(0).workflowName());
        // ...and written to the audit log (the Phase 1 single source of truth).
        assertTrue(audit.query(AuditQuery.all()).stream()
                .anyMatch(e -> e.event().action().equals("workflow:Nightly")));
    }

    @Test
    void scheduledRunsAreAuditedAsAutonomous() {
        AuditLog audit = new RecordStoreAuditLog(new InMemoryRecordStore());
        WorkflowService svc = new WorkflowService(new InMemoryRecordStore(), new InMemoryRecordStore(),
                AppWiring.buildApi(null, "m", new InMemoryStore<>()), audit);
        svc.save(new Workflow("w", "Sched", List.of("x"), TriggerType.SCHEDULE, 60));

        svc.run("w", TriggerType.SCHEDULE);

        assertTrue(audit.query(AuditQuery.all().trigger(com.jarvis.audit.AuditTrigger.AUTONOMOUS))
                .stream().anyMatch(e -> e.event().action().equals("workflow:Sched")));
    }
}
