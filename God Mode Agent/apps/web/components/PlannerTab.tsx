"use client";

import { useState } from "react";
import { getJson, postJson } from "@/lib/api";

type Step = { id: string; name: string; action: string; status: string; depends_on: string[] };
type Workflow = { id: string; goal: string; mode: string; status: string; steps: Step[]; result?: unknown };

export default function PlannerTab() {
  const [goal, setGoal] = useState("research quantum computing then compute 6*7 then write a summary");
  const [mode, setMode] = useState("plan-and-execute");
  const [wf, setWf] = useState<Workflow | null>(null);
  const [status, setStatus] = useState("");

  async function create() {
    const r = await postJson<Workflow>("/workflows", { goal, mode });
    if (r.success) { setWf(r.data); setStatus("created"); } else setStatus(`${r.error.code}: ${r.error.message}`);
  }
  async function run(action: "start" | "resume" | "abort") {
    if (!wf) return;
    const r = await postJson<Workflow>(`/workflows/${wf.id}/${action}`, {});
    if (r.success) { setWf(r.data); setStatus(r.data.status); } else setStatus(`${r.error.code}: ${r.error.message}`);
  }
  async function refresh() {
    if (!wf) return;
    const r = await getJson<Workflow>(`/workflows/${wf.id}`);
    if (r.success) setWf(r.data);
  }

  const color = (s: string) =>
    s === "completed" ? "text-emerald-400" : s === "failed" ? "text-red-400"
      : s === "awaiting_approval" ? "text-amber-400" : "text-zinc-400";

  return (
    <div className="space-y-4">
      <div className="rounded-lg border border-zinc-800 p-4">
        <h2 className="mb-2 text-sm font-medium text-emerald-400">Create workflow</h2>
        <textarea value={goal} onChange={(e) => setGoal(e.target.value)} rows={2} aria-label="goal"
          className="mb-2 w-full rounded-md bg-zinc-900 px-3 py-2 text-sm" />
        <div className="flex flex-wrap gap-2">
          <select value={mode} onChange={(e) => setMode(e.target.value)} aria-label="mode"
            className="rounded-md bg-zinc-900 px-2 py-1.5 text-sm">
            {["direct", "plan-and-execute", "reflect-and-revise", "human-approval-gated"].map((m) => <option key={m}>{m}</option>)}
          </select>
          <button onClick={create} className="rounded-md bg-emerald-600 px-3 py-1.5 text-sm font-medium">Plan</button>
          <button onClick={() => run("start")} disabled={!wf} className="rounded-md bg-zinc-700 px-3 py-1.5 text-sm">Start</button>
          <button onClick={() => run("resume")} disabled={!wf} className="rounded-md bg-zinc-700 px-3 py-1.5 text-sm">Resume</button>
          <button onClick={() => run("abort")} disabled={!wf} className="rounded-md bg-red-700 px-3 py-1.5 text-sm">Abort</button>
          <button onClick={refresh} disabled={!wf} className="rounded-md bg-zinc-700 px-3 py-1.5 text-sm">Refresh</button>
        </div>
      </div>

      {wf && (
        <div className="rounded-lg border border-zinc-800 p-4">
          <h2 className="mb-2 text-sm font-medium text-emerald-400">
            DAG · <span className={color(wf.status)}>{wf.status}</span>
          </h2>
          <ol className="space-y-1 text-sm">
            {wf.steps.map((s) => (
              <li key={s.id} className="flex items-center gap-2">
                <span className="w-8 text-xs text-zinc-500">{s.id}</span>
                <span className={`h-2 w-2 rounded-full ${s.status === "completed" ? "bg-emerald-400" : s.status === "failed" ? "bg-red-500" : "bg-zinc-600"}`} />
                <span className="flex-1">{s.name}</span>
                <span className="text-xs text-zinc-500">{s.action}</span>
                {s.depends_on.length > 0 && <span className="text-[10px] text-zinc-600">⇐ {s.depends_on.join(",")}</span>}
                <span className={`text-xs ${color(s.status)}`}>{s.status}</span>
              </li>
            ))}
          </ol>
        </div>
      )}
      {status && <p className="text-xs text-zinc-400">{status}</p>}
    </div>
  );
}
