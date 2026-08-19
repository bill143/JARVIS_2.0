"use client";

import { useEffect, useState } from "react";
import { getJson, postJson } from "@/lib/api";

type Suite = { suite: string; score: number; passed: boolean; regression: number };
type RunResult = { overall_score: number; gate_passed: boolean; suites: Suite[] };
type Hist = { id: string; created_at: string; suite: string; score: number; passed: boolean };

export default function EvalsTab() {
  const [result, setResult] = useState<RunResult | null>(null);
  const [history, setHistory] = useState<Hist[]>([]);

  async function run() {
    const r = await postJson<RunResult>("/evals/run", { suite: "all", mode: "offline" });
    if (r.success) { setResult(r.data); loadHistory(); }
  }
  async function loadHistory() {
    const r = await getJson<{ history: Hist[] }>("/evals/history");
    if (r.success) setHistory(r.data.history);
  }
  useEffect(() => { loadHistory(); }, []);

  return (
    <div className="space-y-4">
      <button onClick={run} className="rounded-md bg-emerald-600 px-4 py-2 text-sm font-medium">Run all evals (offline gate)</button>

      {result && (
        <div className="rounded-lg border border-zinc-800 p-4 text-sm">
          <h2 className="mb-2 font-medium text-emerald-400">
            Overall {result.overall_score} · gate {result.gate_passed ? "✓ passed" : "✗ failed"}
          </h2>
          <ul className="space-y-1">
            {result.suites.map((s) => (
              <li key={s.suite} className="flex items-center justify-between">
                <span>{s.suite}</span>
                <span className={s.passed ? "text-emerald-400" : "text-red-400"}>
                  {s.score.toFixed(3)} {s.regression > 0 && `(−${s.regression})`} {s.passed ? "✓" : "✗"}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="rounded-lg border border-zinc-800 p-4 text-sm">
        <h2 className="mb-2 font-medium text-emerald-400">Score history (trend)</h2>
        {history.length === 0 && <p className="text-zinc-500">No runs yet.</p>}
        <ul className="space-y-1 text-xs">
          {history.map((h) => (
            <li key={h.id} className="flex justify-between">
              <span className="text-zinc-500">{h.created_at.slice(0, 19)}</span>
              <span>{h.suite}</span>
              <span className={h.passed ? "text-emerald-400" : "text-red-400"}>{h.score.toFixed(3)}</span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
