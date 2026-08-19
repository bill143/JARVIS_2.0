"use client";

import { useEffect, useState } from "react";
import { getJson, postJson } from "@/lib/api";

type Usage = {
  usage: { by_model: { provider: string; model: string; calls: number; tokens: number; cost_usd: number }[];
           by_user: { user_id: string; cost_usd: number; tokens: number }[];
           totals: { calls: number; tokens: number; cost_usd: number; cache_hits: number } };
  budget: { daily_limit_usd: number; spent_today_usd: number; remaining_usd: number; alert: boolean; utilization: number };
};
type Route = { provider: string; model: string; task_type: string; reason: string; downgraded: boolean };

export default function CostTab() {
  const [data, setData] = useState<Usage | null>(null);
  const [route, setRoute] = useState<Route | null>(null);
  const [msg, setMsg] = useState("please reason step by step about this");
  const [limit, setLimit] = useState(25);

  async function load() {
    const r = await getJson<Usage>("/cost/usage");
    if (r.success) setData(r.data);
  }
  useEffect(() => { load(); }, []);

  async function explain() {
    const r = await postJson<Route>("/routing/plan", { message: msg, risk: "low" });
    if (r.success) setRoute(r.data);
  }
  async function setBudget() {
    await postJson("/cost/budget", { daily_usd: limit });
    load();
  }

  return (
    <div className="space-y-4">
      {data && (
        <div className="rounded-lg border border-zinc-800 p-4 text-sm">
          <h2 className="mb-2 font-medium text-emerald-400">Budget</h2>
          <p className={data.budget.alert ? "text-amber-400" : ""}>
            ${data.budget.spent_today_usd} / ${data.budget.daily_limit_usd} today ·
            {" "}{(data.budget.utilization * 100).toFixed(0)}% used {data.budget.alert && "⚠ alert"}
          </p>
          <div className="mt-2 flex gap-2">
            <input type="number" value={limit} onChange={(e) => setLimit(Number(e.target.value))}
              aria-label="daily budget" className="w-28 rounded-md bg-zinc-900 px-2 py-1 text-sm" />
            <button onClick={setBudget} className="rounded-md bg-emerald-600 px-3 py-1 text-sm">Set daily $ (admin)</button>
          </div>
        </div>
      )}

      {data && (
        <div className="rounded-lg border border-zinc-800 p-4 text-sm">
          <h2 className="mb-2 font-medium text-emerald-400">Usage by model</h2>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="text-zinc-500"><tr><th className="p-1">provider/model</th><th className="p-1">calls</th><th className="p-1">tokens</th><th className="p-1">cost $</th></tr></thead>
              <tbody>
                {data.usage.by_model.map((m, i) => (
                  <tr key={i} className="border-t border-zinc-800">
                    <td className="p-1">{m.provider}/{m.model}</td><td className="p-1">{m.calls}</td>
                    <td className="p-1">{m.tokens}</td><td className="p-1">{m.cost_usd}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="mt-2 text-xs text-zinc-500">
            totals: {data.usage.totals.calls} calls · {data.usage.totals.tokens} tokens · ${data.usage.totals.cost_usd} · {data.usage.totals.cache_hits} cache hits
          </p>
        </div>
      )}

      <div className="rounded-lg border border-zinc-800 p-4 text-sm">
        <h2 className="mb-2 font-medium text-emerald-400">Explain a route</h2>
        <div className="flex gap-2">
          <input value={msg} onChange={(e) => setMsg(e.target.value)} aria-label="message"
            className="flex-1 rounded-md bg-zinc-900 px-3 py-2 text-sm" />
          <button onClick={explain} className="rounded-md bg-zinc-700 px-4 py-2 text-sm">Explain</button>
        </div>
        {route && (
          <p className="mt-2 text-xs">
            → <span className="text-emerald-400">{route.provider}/{route.model}</span> (task {route.task_type}
            {route.downgraded && ", downgraded"}) — {route.reason}
          </p>
        )}
      </div>
    </div>
  );
}
