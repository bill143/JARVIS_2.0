"use client";

import { useEffect, useState } from "react";
import { getJson, postJson } from "@/lib/api";

type Status = {
  modes: Record<string, boolean>;
  retention: { data_class: string; retention_days: number; region: string; deletion_window_days: number }[];
  audit: { ok: boolean; entries?: number };
};

export default function ComplianceTab() {
  const [status, setStatus] = useState<Status | null>(null);
  const [msg, setMsg] = useState("");

  async function load() {
    const r = await getJson<Status>("/compliance/status");
    if (r.success) setStatus(r.data);
  }
  useEffect(() => { load(); }, []);

  async function toggle(key: string, value: boolean) {
    await postJson("/compliance/toggle", { key, value });
    load();
  }
  async function exportEvidence(fmt: string) {
    const r = await postJson<{ id: string; record_count: number; digest: string }>(`/compliance/export?fmt=${fmt}`, {});
    setMsg(r.success ? `evidence ${r.data.id}: ${r.data.record_count} records, digest ${r.data.digest.slice(0, 12)}…` : `${r.error.code}: ${r.error.message}`);
  }
  async function validate() {
    const r = await getJson<{ ok: boolean; entries: number }>("/compliance/validate");
    setMsg(r.success ? (r.data.ok ? `✓ audit chain intact (${r.data.entries} entries)` : "✗ TAMPERED") : `${r.error.code}`);
  }

  return (
    <div className="space-y-4">
      {status && (
        <>
          <div className="rounded-lg border border-zinc-800 p-4 text-sm">
            <h2 className="mb-2 font-medium text-emerald-400">Compliance modes (admin toggles)</h2>
            <div className="space-y-1">
              {Object.entries(status.modes).map(([k, v]) => (
                <label key={k} className="flex items-center gap-2">
                  <input type="checkbox" checked={v} onChange={(e) => toggle(k, e.target.checked)} aria-label={k} />
                  {k} — <span className={v ? "text-emerald-400" : "text-zinc-500"}>{v ? "on" : "off"}</span>
                </label>
              ))}
            </div>
          </div>

          <div className="rounded-lg border border-zinc-800 p-4 text-sm">
            <h2 className="mb-2 font-medium text-emerald-400">Retention policies</h2>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="text-zinc-500"><tr><th className="p-1">class</th><th className="p-1">days</th><th className="p-1">region</th><th className="p-1">deletion window</th></tr></thead>
                <tbody>
                  {status.retention.map((p) => (
                    <tr key={p.data_class} className="border-t border-zinc-800">
                      <td className="p-1">{p.data_class}</td><td className="p-1">{p.retention_days}</td>
                      <td className="p-1">{p.region}</td><td className="p-1">{p.deletion_window_days}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <div className="flex flex-wrap gap-2">
            <button onClick={validate} className="rounded-md bg-emerald-600 px-3 py-1.5 text-sm">Validate audit chain</button>
            <button onClick={() => exportEvidence("json")} className="rounded-md bg-zinc-700 px-3 py-1.5 text-sm">Export evidence (JSON)</button>
            <button onClick={() => exportEvidence("csv")} className="rounded-md bg-zinc-700 px-3 py-1.5 text-sm">Export (CSV)</button>
          </div>
        </>
      )}
      {msg && <p className="text-xs text-zinc-400">{msg}</p>}
    </div>
  );
}
