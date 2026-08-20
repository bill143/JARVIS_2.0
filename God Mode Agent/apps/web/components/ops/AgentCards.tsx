"use client";

// Agent cards driven entirely by the activity log: status is derived
// (active = row in last 5 min, else idle; no rows = "no data yet").
// There is deliberately no hardcoded status string anywhere here.

import type { AgentSummary } from "@/lib/activity";
import { timeAgo } from "@/lib/activity";

const STATUS_STYLE: Record<AgentSummary["status"], { dot: string; label: string; text: string }> = {
  active: { dot: "bg-emerald-400 animate-pulse", label: "active", text: "text-emerald-400" },
  idle: { dot: "bg-zinc-500", label: "idle", text: "text-zinc-400" },
  no_data: { dot: "bg-zinc-700", label: "no data yet", text: "text-zinc-500" },
};

export default function AgentCards({ agents }: { agents: AgentSummary[] | null }) {
  if (!agents) {
    return <p className="text-[11px] text-zinc-500">loading agent activity…</p>;
  }
  return (
    <div className="grid grid-cols-2 gap-2 md:grid-cols-4" aria-label="agent activity">
      {agents.map((a) => {
        const s = STATUS_STYLE[a.status];
        return (
          <div key={a.agent} className="rounded-lg border border-zinc-800 p-3">
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium tracking-tight text-zinc-200">{a.agent}</span>
              <span className={`flex items-center gap-1.5 text-[10px] uppercase tracking-wide ${s.text}`}>
                <span className={`h-1.5 w-1.5 rounded-full ${s.dot}`} />
                {s.label}
              </span>
            </div>
            <p className="mt-2 truncate text-[11px] text-zinc-500" title={a.last_task ?? undefined}>
              {a.last_task ? `${a.last_status === "failed" ? "✗ " : ""}${a.last_task}` : "no tasks recorded"}
            </p>
            <div className="mt-1 flex justify-between text-[11px]">
              <span className="text-zinc-500">{timeAgo(a.last_timestamp)}</span>
              <span className="font-mono text-zinc-400">
                {a.today.total > 0
                  ? `${a.today.completed} ok${a.today.failed ? ` · ${a.today.failed} failed` : ""} today`
                  : "0 today"}
              </span>
            </div>
          </div>
        );
      })}
    </div>
  );
}
