"use client";

import { useCallback, useEffect, useState } from "react";
import { getJson, postJson, putJson } from "@/lib/api";
import { useAuth } from "@/lib/auth";

type SuiteRow = {
  suite_result_id: string;
  suite_name: string;
  score: number;
  threshold: number;
  passed: boolean;
  weight: number;
  critical: boolean;
  duration_ms: number;
  token_total: number;
  estimated_cost_usd: number;
  sample_size: number;
  stddev: number;
  failure_count: number;
  scope: string;
  investigate_test: string;
  investigate_module: string;
};
type Run = {
  id: string;
  created_at: string;
  status: string;
  commit_sha: string;
  branch: string;
  app_version: string;
  env_profile: string;
  dataset_version: string;
  triggered_by: string;
  provider_meta: Record<string, unknown>;
  resolved_models: Record<string, unknown>;
  overall_score: number;
  overall_gate_pass: boolean;
  gate_blocked: boolean;
  gate_explanation: string[];
  duration_ms: number;
  suite_count: number;
  suites: SuiteRow[];
};
type CaseRow = {
  case_id: string;
  case_name: string;
  passed: boolean;
  score: number;
  expected_summary: string;
  actual_summary: string;
  failure_reason: string;
  investigate_file_path: string;
  investigate_module: string;
};
type RunSummary = Omit<Run, "suites">;
type GatePolicy = {
  block_deploy_on_fail: boolean;
  global_threshold: number;
  per_suite_threshold: Record<string, number>;
  per_suite_weight: Record<string, number>;
  critical_suites: string[];
};
type SuiteMeta = { suite: string; scope: string; investigate_test: string; investigate_module: string };
type Trends = { overall: { created_at: string; score: number; gate_pass: boolean }[] };

function TrendChart({ points }: { points: { created_at: string; score: number; gate_pass: boolean }[] }) {
  if (points.length < 2) return <p className="text-xs text-zinc-500">Run at least two evals to see a trend.</p>;
  const w = 520;
  const h = 120;
  const pad = 8;
  const xs = (i: number) => pad + (i * (w - 2 * pad)) / (points.length - 1);
  const ys = (v: number) => h - pad - v * (h - 2 * pad);
  const path = points.map((p, i) => `${i === 0 ? "M" : "L"}${xs(i).toFixed(1)},${ys(p.score).toFixed(1)}`).join(" ");
  return (
    <svg viewBox={`0 0 ${w} ${h}`} className="w-full" role="img" aria-label="overall score trend">
      <line x1={pad} y1={ys(1)} x2={w - pad} y2={ys(1)} stroke="#3f3f46" strokeDasharray="3 3" />
      <path d={path} fill="none" stroke="#34d399" strokeWidth={2} />
      {points.map((p, i) => (
        <circle key={i} cx={xs(i)} cy={ys(p.score)} r={3} fill={p.gate_pass ? "#34d399" : "#f87171"} />
      ))}
    </svg>
  );
}

export default function EvalsTab() {
  const { hasRole } = useAuth();
  const isAdmin = hasRole("admin");

  const [suitesMeta, setSuitesMeta] = useState<SuiteMeta[]>([]);
  const [policy, setPolicy] = useState<GatePolicy | null>(null);
  const [current, setCurrent] = useState<Run | null>(null);
  const [runs, setRuns] = useState<RunSummary[]>([]);
  const [trends, setTrends] = useState<Trends | null>(null);
  const [drawer, setDrawer] = useState<{ suite: SuiteRow; cases: CaseRow[] } | null>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState("");
  const [err, setErr] = useState("");

  const [onlyImperfect, setOnlyImperfect] = useState(false);
  const [filterSuite, setFilterSuite] = useState("");
  const [filterStatus, setFilterStatus] = useState("");
  const [selectedSuite, setSelectedSuite] = useState("");

  const loadHistory = useCallback(async () => {
    const params = new URLSearchParams({ limit: "50" });
    if (onlyImperfect) params.set("below_score", "1");
    if (filterSuite) params.set("suite", filterSuite);
    if (filterStatus) params.set("status", filterStatus);
    const [r, t] = await Promise.all([
      getJson<{ runs: RunSummary[] }>(`/evals/v2/runs?${params.toString()}`),
      getJson<Trends>("/evals/v2/trends"),
    ]);
    if (r.success) setRuns(r.data.runs);
    if (t.success) setTrends(t.data);
  }, [onlyImperfect, filterSuite, filterStatus]);

  const boot = useCallback(async () => {
    const [sm, gp] = await Promise.all([
      getJson<{ suites: SuiteMeta[] }>("/evals/v2/suites"),
      getJson<GatePolicy>("/evals/v2/gate-policy"),
    ]);
    if (sm.success) setSuitesMeta(sm.data.suites);
    if (gp.success) setPolicy(gp.data);
    await loadHistory();
  }, [loadHistory]);

  useEffect(() => {
    boot();
  }, [boot]);
  useEffect(() => {
    loadHistory();
  }, [loadHistory]);

  async function runAll() {
    setBusy(true);
    setErr("");
    const r = await postJson<Run>("/evals/v2/run", { branch: "feat/god-mode-agent" });
    setBusy(false);
    if (r.success) {
      setCurrent(r.data);
      setMsg(`Run ${r.data.id} — gate ${r.data.gate_blocked ? "BLOCKED" : r.data.overall_gate_pass ? "PASS" : "FAIL"}`);
      loadHistory();
    } else setErr(`${r.error.code}: ${r.error.message}`);
  }

  async function runSuite() {
    if (!selectedSuite) return;
    setBusy(true);
    setErr("");
    const r = await postJson<Run>(`/evals/v2/run/${selectedSuite}`, {});
    setBusy(false);
    if (r.success) {
      setCurrent(r.data);
      loadHistory();
    } else setErr(`${r.error.code}: ${r.error.message}`);
  }

  async function openRun(id: string) {
    const r = await getJson<Run>(`/evals/v2/runs/${id}`);
    if (r.success) setCurrent(r.data);
    else setErr(`${r.error.code}: ${r.error.message}`);
  }

  async function rerun(id: string) {
    setBusy(true);
    const r = await postJson<Run>(`/evals/v2/runs/${id}/rerun`, {});
    setBusy(false);
    if (r.success) {
      setCurrent(r.data);
      loadHistory();
    } else setErr(`${r.error.code}: ${r.error.message}`);
  }

  async function openDrawer(suite: SuiteRow) {
    if (!current) return;
    const r = await getJson<{ cases: CaseRow[] }>(`/evals/v2/runs/${current.id}/suites/${suite.suite_name}/cases`);
    setDrawer({ suite, cases: r.success ? r.data.cases : [] });
  }

  async function toggleBlockDeploy() {
    if (!policy) return;
    const r = await putJson<GatePolicy>("/evals/v2/gate-policy", { block_deploy_on_fail: !policy.block_deploy_on_fail });
    if (r.success) {
      setPolicy(r.data);
      setMsg(`Block-deploy-on-fail ${r.data.block_deploy_on_fail ? "ON" : "OFF"}.`);
    } else setErr(`${r.error.code}: ${r.error.message}`);
  }

  function copy(text: string) {
    navigator.clipboard?.writeText(text);
    setMsg(`Copied: ${text}`);
  }

  return (
    <div className="space-y-4">
      {/* Controls */}
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <button onClick={runAll} disabled={busy} className="rounded-md bg-emerald-600 px-3 py-1.5 font-medium disabled:opacity-50">
          {busy ? "Running…" : "Run all evals"}
        </button>
        <select value={selectedSuite} onChange={(e) => setSelectedSuite(e.target.value)} className="rounded bg-zinc-800 p-1.5 text-xs">
          <option value="">(pick a suite)</option>
          {suitesMeta.map((s) => (
            <option key={s.suite} value={s.suite}>{s.suite}</option>
          ))}
        </select>
        <button onClick={runSuite} disabled={busy || !selectedSuite} className="rounded-md bg-zinc-700 px-3 py-1.5 text-xs disabled:opacity-50">
          Run suite
        </button>
        {policy && (
          <label className="ml-auto flex items-center gap-2 text-xs">
            <input type="checkbox" checked={policy.block_deploy_on_fail} disabled={!isAdmin} onChange={toggleBlockDeploy} />
            Block deploy on fail
          </label>
        )}
      </div>

      {err && <p className="rounded border border-red-800 bg-red-950/40 p-2 text-xs text-red-300">{err}</p>}
      {msg && !err && <p className="text-xs text-zinc-400">{msg}</p>}

      {/* Gate status card */}
      {current && (
        <div className={`rounded-lg border p-4 text-sm ${current.gate_blocked ? "border-red-800" : current.overall_gate_pass ? "border-emerald-800" : "border-amber-800"}`}>
          <div className="flex items-center justify-between">
            <h2 className="font-medium">
              Gate:{" "}
              <span className={current.gate_blocked ? "text-red-400" : current.overall_gate_pass ? "text-emerald-400" : "text-amber-400"}>
                {current.gate_blocked ? "BLOCKED" : current.overall_gate_pass ? "PASS" : "FAIL"}
              </span>
            </h2>
            <span className="text-xs text-zinc-400">overall {current.overall_score.toFixed(3)} · {current.duration_ms.toFixed(0)}ms</span>
          </div>
          <ul className="mt-1 list-disc pl-5 text-xs text-zinc-400">
            {current.gate_explanation.map((r, i) => <li key={i}>{r}</li>)}
          </ul>
          <p className="mt-2 text-[11px] text-zinc-500">
            {current.created_at.slice(0, 19)} · {current.branch || "(no branch)"}
            {current.commit_sha ? ` @ ${current.commit_sha.slice(0, 8)}` : ""} · env {current.env_profile} ·
            model {String((current.resolved_models as { default?: string }).default ?? "?")} · by {current.triggered_by}
            {" "}<button onClick={() => rerun(current.id)} className="ml-2 text-emerald-400 hover:underline">Rerun exact config</button>
          </p>
        </div>
      )}

      {/* Suite table */}
      {current && (
        <div className="overflow-x-auto rounded-lg border border-zinc-800 p-4">
          <h2 className="mb-2 text-sm font-medium text-emerald-400">Suites</h2>
          <table className="w-full text-left text-xs">
            <thead className="text-zinc-500">
              <tr>
                <th className="p-1">suite</th><th className="p-1">score</th><th className="p-1">threshold</th>
                <th className="p-1">result</th><th className="p-1">duration</th><th className="p-1">~tokens/cost</th><th className="p-1">failed</th>
              </tr>
            </thead>
            <tbody>
              {current.suites.map((s) => (
                <tr
                  key={s.suite_result_id}
                  tabIndex={0}
                  role="button"
                  onClick={() => openDrawer(s)}
                  onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); openDrawer(s); } }}
                  className="cursor-pointer border-t border-zinc-800 hover:bg-zinc-800/50 focus:bg-zinc-800/60 focus:outline-none"
                >
                  <td className="p-1">{s.suite_name}{s.critical && <span className="ml-1 text-[10px] text-amber-400">critical</span>}</td>
                  <td className="p-1">{s.score.toFixed(3)}</td>
                  <td className="p-1">{s.threshold.toFixed(2)}</td>
                  <td className={`p-1 ${s.passed ? "text-emerald-400" : "text-red-400"}`}>{s.passed ? "✓ pass" : "✗ fail"}</td>
                  <td className="p-1">{s.duration_ms.toFixed(0)}ms</td>
                  <td className="p-1">~{s.token_total}t / ${s.estimated_cost_usd.toFixed(6)}</td>
                  <td className="p-1">{s.failure_count}/{s.sample_size}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="mt-1 text-[11px] text-zinc-500">Click a row for scope, thresholds, failed cases, and where to investigate.</p>
        </div>
      )}

      {/* Trend chart */}
      <div className="rounded-lg border border-zinc-800 p-4">
        <h2 className="mb-2 text-sm font-medium text-emerald-400">Overall score trend</h2>
        {trends ? <TrendChart points={trends.overall} /> : <p className="text-xs text-zinc-500">Loading…</p>}
      </div>

      {/* History */}
      <div className="rounded-lg border border-zinc-800 p-4">
        <div className="mb-2 flex flex-wrap items-center gap-3">
          <h2 className="text-sm font-medium text-emerald-400">Run history</h2>
          <label className="flex items-center gap-1 text-xs">
            <input type="checkbox" checked={onlyImperfect} onChange={(e) => setOnlyImperfect(e.target.checked)} />
            only score &lt; 1.0
          </label>
          <select value={filterSuite} onChange={(e) => setFilterSuite(e.target.value)} className="rounded bg-zinc-800 p-1 text-xs">
            <option value="">all suites</option>
            {suitesMeta.map((s) => <option key={s.suite} value={s.suite}>{s.suite}</option>)}
          </select>
          <select value={filterStatus} onChange={(e) => setFilterStatus(e.target.value)} className="rounded bg-zinc-800 p-1 text-xs">
            <option value="">any status</option>
            <option value="passed">passed</option>
            <option value="failed">failed</option>
            <option value="blocked">blocked</option>
          </select>
        </div>
        {runs.length === 0 ? (
          <p className="text-xs text-zinc-500">No runs match.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="text-zinc-500">
                <tr><th className="p-1">date / time</th><th className="p-1">overall</th><th className="p-1">gate</th><th className="p-1">env</th><th className="p-1">branch</th><th className="p-1"></th></tr>
              </thead>
              <tbody>
                {runs.map((r) => (
                  <tr key={r.id} className="border-t border-zinc-800">
                    <td className="p-1">{r.created_at.slice(0, 19).replace("T", " ")}</td>
                    <td className="p-1">{r.overall_score.toFixed(3)}</td>
                    <td className={`p-1 ${r.gate_blocked ? "text-red-400" : r.overall_gate_pass ? "text-emerald-400" : "text-amber-400"}`}>
                      {r.gate_blocked ? "BLOCKED" : r.overall_gate_pass ? "PASS" : "FAIL"}
                    </td>
                    <td className="p-1">{r.env_profile}</td>
                    <td className="p-1">{r.branch || "—"}</td>
                    <td className="p-1">
                      <button onClick={() => openRun(r.id)} className="mr-2 text-emerald-400 hover:underline">Open</button>
                      <button onClick={() => rerun(r.id)} className="text-zinc-400 hover:underline">Rerun</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Suite detail drawer */}
      {drawer && (
        <div className="fixed inset-0 z-50 flex justify-end bg-black/50" onClick={() => setDrawer(null)}>
          <div className="h-full w-full max-w-md overflow-y-auto border-l border-zinc-700 bg-zinc-900 p-4 text-sm" onClick={(e) => e.stopPropagation()}>
            <div className="mb-2 flex items-center justify-between">
              <h3 className="font-medium text-emerald-400">{drawer.suite.suite_name}</h3>
              <button onClick={() => setDrawer(null)} className="text-zinc-400">✕</button>
            </div>
            <p className="text-xs text-zinc-400">{drawer.suite.scope}</p>
            <div className="mt-2 grid grid-cols-2 gap-1 text-xs text-zinc-300">
              <div>score: <b>{drawer.suite.score.toFixed(3)}</b></div>
              <div>threshold: <b>{drawer.suite.threshold.toFixed(2)}</b></div>
              <div>result: <b className={drawer.suite.passed ? "text-emerald-400" : "text-red-400"}>{drawer.suite.passed ? "pass" : "fail"}</b></div>
              <div>stddev: <b>{drawer.suite.stddev.toFixed(3)}</b></div>
              <div>sample size: <b>{drawer.suite.sample_size}</b></div>
              <div>failed: <b>{drawer.suite.failure_count}</b></div>
            </div>

            <h4 className="mt-3 text-xs font-medium text-zinc-400">Where to investigate</h4>
            <div className="space-y-1 text-xs">
              <button onClick={() => copy(drawer.suite.investigate_test)} className="block w-full truncate rounded bg-zinc-800 p-1 text-left font-mono hover:bg-zinc-700" title="click to copy">
                {drawer.suite.investigate_test}
              </button>
              <button onClick={() => copy(drawer.suite.investigate_module)} className="block w-full truncate rounded bg-zinc-800 p-1 text-left font-mono hover:bg-zinc-700" title="click to copy">
                {drawer.suite.investigate_module}
              </button>
            </div>

            <h4 className="mt-3 text-xs font-medium text-zinc-400">Cases</h4>
            <div className="space-y-1">
              {drawer.cases.length === 0 && <p className="text-xs text-zinc-500">No case detail.</p>}
              {drawer.cases.map((c) => (
                <div key={c.case_id} className="rounded border border-zinc-800 p-2 text-xs">
                  <div className="flex justify-between">
                    <span className="font-mono">{c.case_name}</span>
                    <span className={c.passed ? "text-emerald-400" : "text-red-400"}>{c.score.toFixed(2)} {c.passed ? "✓" : "✗"}</span>
                  </div>
                  {!c.passed && <p className="mt-1 text-red-300">{c.failure_reason}</p>}
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
