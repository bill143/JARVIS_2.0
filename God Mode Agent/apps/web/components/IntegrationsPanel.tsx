"use client";

import { useCallback, useEffect, useState } from "react";
import { getJson } from "@/lib/api";

type Integration = { name: string; category: string; status: string; hint: string };

const chip = (s: string) =>
  s === "connected" || s === "enabled"
    ? "bg-emerald-900/50 text-emerald-300"
    : s === "planned"
      ? "bg-zinc-800 text-zinc-500"
      : "bg-amber-900/40 text-amber-300";

const GROUPS: { key: string; label: string }[] = [
  { key: "AI Provider", label: "AI Providers" },
  { key: "Tool", label: "Tools" },
  { key: "Planned", label: "Planned" },
];

export default function IntegrationsPanel() {
  const [rows, setRows] = useState<Integration[]>([]);
  const [err, setErr] = useState("");
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setErr("");
    const r = await getJson<{ integrations: Integration[] }>("/integrations/catalog");
    if (r.success) setRows(r.data.integrations);
    else setErr(`${r.error.code}: ${r.error.message}`);
    setLoading(false);
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-medium text-emerald-400">Integrations</h2>
        <button onClick={load} className="rounded bg-zinc-700 px-2 py-1 text-xs">Refresh</button>
      </div>
      {loading && <p className="text-xs text-zinc-500">Loading…</p>}
      {err && <p className="text-xs text-red-400">{err}</p>}
      {!loading &&
        GROUPS.map((g) => {
          const items = rows.filter((r) => r.category === g.key);
          if (!items.length) return null;
          return (
            <div key={g.key} className="rounded-lg border border-zinc-800 p-3">
              <h3 className="mb-1 text-xs uppercase tracking-wide text-zinc-500">{g.label}</h3>
              <ul className="space-y-1">
                {items.map((it) => (
                  <li key={it.name} className="flex items-center justify-between gap-2 text-sm">
                    <span>{it.name}</span>
                    <span className="flex items-center gap-2">
                      <span className="text-[11px] text-zinc-500">{it.hint}</span>
                      <span className={`rounded px-1.5 py-0.5 text-[10px] ${chip(it.status)}`}>{it.status}</span>
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          );
        })}
      <p className="text-[11px] text-zinc-600">
        Provider keys are configured in <span className="text-zinc-400">.env</span> (the Settings API-management panel
        arrives in the Settings batch). Planned connectors are a v1 catalog scaffold and are not yet wired.
      </p>
    </div>
  );
}
