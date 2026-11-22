"use client";

import { useMemo, useState } from "react";
import type { AgentTask } from "@/lib/types/tasks";

const fixtureTasks: AgentTask[] = [
  { id: "task-1", title: "Initial ingest", status: "queued", steps: ["Validate inputs", "Queue job", "Await worker"], error: undefined },
  { id: "task-2", title: "Policy sync", status: "running", steps: ["Pull configuration", "Apply policy set", "Verify output"], error: undefined },
  { id: "task-3", title: "Knowledge refresh", status: "done", steps: ["Fetch corpus", "Embed updates", "Confirm index"], error: undefined },
  { id: "task-4", title: "Report generation", status: "failed", steps: ["Build dataset", "Generate summary", "Publish results"], error: "Model output timed out while serializing report artifacts." },
  { id: "task-5", title: "Retry policy check", status: "retrying", steps: ["Retry after timeout", "Re-run rule validation", "Compare output"], error: "Transient API timeout; retry scheduled." },
  { id: "task-6", title: "Recovery pass", status: "recovered", steps: ["Detect failed dependency", "Restore checkpoint", "Resume pipeline"], error: undefined },
];

const statusStyles: Record<AgentTask["status"], string> = {
  queued: "bg-slate-700 text-slate-100",
  running: "bg-[var(--jarvis-primary)] text-slate-950",
  done: "bg-[var(--jarvis-success)] text-slate-950",
  failed: "bg-[var(--jarvis-error)] text-white",
  retrying: "bg-[var(--jarvis-warn)] text-slate-950",
  recovered: "bg-emerald-500 text-slate-950",
};

export function TaskQueuePanel({ tasks = fixtureTasks }: { tasks?: AgentTask[] }) {
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const visibleTasks = useMemo(() => tasks.length > 0 ? tasks : fixtureTasks, [tasks]);

  return (
    <div className="space-y-3">
      {visibleTasks.map((task) => (
        <div key={task.id} className="rounded-xl border border-[var(--jarvis-border)] bg-[var(--jarvis-surface)] p-3">
          <div className="flex items-center justify-between gap-2">
            <button
              type="button"
              className="flex-1 text-left"
              onClick={() => setExpanded((prev) => ({ ...prev, [task.id]: !prev[task.id] }))}
              aria-label={`Toggle details for ${task.title}`}
            >
              <div className="text-sm font-medium text-[var(--jarvis-text)]">{task.title}</div>
            </button>
            <span className={`rounded-full px-2 py-1 text-[10px] font-semibold uppercase tracking-[0.12em] ${statusStyles[task.status]}`}>
              {task.status}
            </span>
          </div>
          {expanded[task.id] && (
            <div className="mt-3 space-y-2">
              <ul className="list-disc space-y-1 pl-5 text-xs text-[var(--jarvis-muted)]">
                {task.steps.map((step) => <li key={step}>{step}</li>)}
              </ul>
              {task.error && <div className="rounded-md bg-[var(--jarvis-error)]/10 p-2 text-xs text-[var(--jarvis-error)]">{task.error}</div>}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
