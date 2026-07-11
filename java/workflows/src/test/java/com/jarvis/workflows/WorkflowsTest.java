package com.jarvis.workflows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.memory.InMemoryRecordStore;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class WorkflowsTest {

    @Test
    void storeSavesListsAndDeletes() {
        WorkflowStore store = new WorkflowStore(new InMemoryRecordStore());
        store.save(new Workflow("w1", "Morning brief", List.of("get news", "check mail"),
                TriggerType.MANUAL, 0));
        assertEquals(1, store.list().size());
        assertEquals("Morning brief", store.get("w1").orElseThrow().name());
        store.delete("w1");
        assertTrue(store.list().isEmpty());
    }

    @Test
    void runnerExecutesStepsInOrderAndRecordsTheRun() {
        WorkflowRunStore runs = new WorkflowRunStore(new InMemoryRecordStore());
        WorkflowRunner runner = new WorkflowRunner(runs);
        Workflow wf = new Workflow("w", "wf", List.of("a", "b"), TriggerType.MANUAL, 0);

        WorkflowRun run = runner.run(wf, step -> "did " + step, 1);
        assertTrue(run.ok());
        assertEquals(List.of("did a", "did b"), run.steps().stream().map(StepResult::output).toList());
        assertEquals(1, runs.recent(10).size());
    }

    @Test
    void runnerStopsAtTheFirstFailingStep() {
        WorkflowRunner runner = new WorkflowRunner(null);
        Workflow wf = new Workflow("w", "wf", List.of("ok", "boom", "never"), TriggerType.MANUAL, 0);
        WorkflowRun run = runner.run(wf, step -> {
            if (step.equals("boom")) {
                throw new IllegalStateException("kaboom");
            }
            return "ok";
        }, 1);
        assertFalse(run.ok());
        assertEquals(2, run.steps().size());   // ok + boom, then stopped
        assertFalse(run.steps().get(1).ok());
    }

    @Test
    void runnerHardCapsStepCount() {
        WorkflowRunner runner = new WorkflowRunner(null);
        AtomicInteger calls = new AtomicInteger();
        List<String> manySteps = new java.util.ArrayList<>();
        for (int i = 0; i < WorkflowRunner.MAX_STEPS + 10; i++) {
            manySteps.add("s" + i);
        }
        runner.run(new Workflow("w", "wf", manySteps, TriggerType.MANUAL, 0),
                step -> { calls.incrementAndGet(); return "x"; }, 1);
        assertEquals(WorkflowRunner.MAX_STEPS, calls.get());   // capped, not 30
    }

    @Test
    void schedulerIgnoresManualAndSchedulesScheduleTriggers() {
        WorkflowScheduler scheduler = new WorkflowScheduler();
        try {
            scheduler.schedule(new Workflow("m", "manual", List.of(), TriggerType.MANUAL, 0), () -> { });
            assertEquals(0, scheduler.scheduledCount());
            scheduler.schedule(new Workflow("s", "sched", List.of(), TriggerType.SCHEDULE, 60), () -> { });
            assertEquals(1, scheduler.scheduledCount());
            scheduler.cancel("s");
            assertEquals(0, scheduler.scheduledCount());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    @Timeout(5)
    void schedulerActuallyFiresTheTask() throws Exception {
        WorkflowScheduler scheduler = new WorkflowScheduler();
        try {
            CountDownLatch latch = new CountDownLatch(2);
            scheduler.scheduleEvery("t", latch::countDown, 30);
            assertTrue(latch.await(3, TimeUnit.SECONDS), "scheduled task should fire repeatedly");
        } finally {
            scheduler.shutdown();
        }
    }
}
