"use client";

import { useEffect, useState } from "react";
import { getJson } from "@/lib/api";

type Entry = {
  id: number;
  ts: string;
  category: string;
  actor: string;
  action: string;
  detail: Record<string, unknown>;
  hash: string;
};

export default function AuditExplorer() {
  const [entries, setEntries] = useState<Entry[]>([]);
  const [category, setCategory] = useState("");
  const [verify, setVerify] = useState<{ ok: boolean; entries: number } | null>(
    null,
  );

  async function load() {
    const q = category ? `?category=${encodeURIComponent(category)}` : "";
    const resp = await getJson<{ entries: Entry[] }>(`/audit${q}`);
    if (resp.success) setEntries(resp.data.entries);
  }

  async function checkChain() {
    const resp = await getJson<{ ok: boolean; entries: number }>(
      "/audit/verify",
    );
    if (resp.success) setVerify(resp.data);
  }

  useEffect(() => {
    load();
  }, []);

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <select
          value={category}
          onChange={(e) => setCategory(e.target.value)}
          aria-label="category filter"
          className="rounded-md bg-zinc-900 px-2 py-1.5 text-sm"
        >
          {["", "auth", "policy", "tool", "approval", "memory", "safety"].map(
            (c) => (
              <option key={c} value={c}>
                {c || "all categories"}
              </option>
            ),
          )}
        </select>
        <button
          onClick={load}
          className="rounded-md bg-zinc-700 px-3 py-1.5 text-sm"
        >
          Filter
        </button>
        <button
          onClick={checkChain}
          className="rounded-md bg-emerald-600 px-3 py-1.5 text-sm"
        >
          Verify chain (admin)
        </button>
        {verify && (
          <span
            className={`text-sm ${verify.ok ? "text-emerald-400" : "text-red-400"}`}
          >
            {verify.ok ? `✓ intact (${verify.entries} entries)` : "✗ TAMPERED"}
          </span>
        )}
      </div>
      <div className="overflow-x-auto rounded-lg border border-zinc-800">
        <table className="w-full text-left text-xs">
          <thead className="text-zinc-500">
            <tr>
              <th className="p-2">ts</th>
              <th className="p-2">category</th>
              <th className="p-2">actor</th>
              <th className="p-2">action</th>
              <th className="p-2">detail</th>
            </tr>
          </thead>
          <tbody>
            {entries.map((e) => (
              <tr key={e.id} className="border-t border-zinc-800">
                <td className="p-2 text-zinc-500">{e.ts.slice(11, 19)}</td>
                <td className="p-2">
                  <span className="rounded bg-zinc-800 px-1.5 py-0.5">
                    {e.category}
                  </span>
                </td>
                <td className="p-2">{e.actor}</td>
                <td className="p-2">{e.action}</td>
                <td className="p-2 text-zinc-500">
                  {JSON.stringify(e.detail).slice(0, 100)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
