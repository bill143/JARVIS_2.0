"use client";

import { useEffect, useState } from "react";
import { getJson, postJson } from "@/lib/api";

type Rule = {
  id: string;
  scope: string;
  subject: string;
  tool: string;
  action: string;
  priority: number;
  note: string;
};

export default function PolicyConsole() {
  const [rules, setRules] = useState<Rule[]>([]);
  const [status, setStatus] = useState("");
  const [form, setForm] = useState({
    subject: "*",
    tool: "*",
    action: "deny",
    priority: 100,
    note: "",
  });

  async function load() {
    const resp = await getJson<{ rules: Rule[] }>("/policy/rules");
    if (resp.success) setRules(resp.data.rules);
    else setStatus(`${resp.error.code}: ${resp.error.message}`);
  }

  useEffect(() => {
    load();
  }, []);

  async function addRule() {
    const resp = await postJson("/policy/rules", { scope: "org", ...form });
    setStatus(
      resp.success ? "rule added" : `${resp.error.code}: ${resp.error.message}`,
    );
    load();
  }

  return (
    <div className="space-y-4">
      <div className="rounded-lg border border-zinc-800 p-4">
        <h2 className="mb-2 text-sm font-medium text-emerald-400">
          Add policy rule (admin)
        </h2>
        <div className="grid grid-cols-2 gap-2 md:grid-cols-5">
          <input
            value={form.subject}
            onChange={(e) => setForm({ ...form, subject: e.target.value })}
            placeholder="subject (* / role / user)"
            aria-label="subject"
            className="rounded-md bg-zinc-900 px-2 py-1.5 text-sm"
          />
          <input
            value={form.tool}
            onChange={(e) => setForm({ ...form, tool: e.target.value })}
            placeholder="tool (* or name)"
            aria-label="tool"
            className="rounded-md bg-zinc-900 px-2 py-1.5 text-sm"
          />
          <select
            value={form.action}
            onChange={(e) => setForm({ ...form, action: e.target.value })}
            aria-label="action"
            className="rounded-md bg-zinc-900 px-2 py-1.5 text-sm"
          >
            {["allow", "deny", "require_approval", "redact_and_allow"].map(
              (a) => (
                <option key={a}>{a}</option>
              ),
            )}
          </select>
          <input
            type="number"
            value={form.priority}
            onChange={(e) =>
              setForm({ ...form, priority: Number(e.target.value) })
            }
            placeholder="priority"
            aria-label="priority"
            className="rounded-md bg-zinc-900 px-2 py-1.5 text-sm"
          />
          <button
            onClick={addRule}
            className="rounded-md bg-emerald-600 px-3 py-1.5 text-sm font-medium"
          >
            Add
          </button>
        </div>
      </div>

      <div className="rounded-lg border border-zinc-800 p-4">
        <h2 className="mb-2 text-sm font-medium text-emerald-400">
          Rules ({rules.length})
        </h2>
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="text-zinc-500">
              <tr>
                <th className="p-1">priority</th>
                <th className="p-1">subject</th>
                <th className="p-1">tool</th>
                <th className="p-1">action</th>
                <th className="p-1">note</th>
              </tr>
            </thead>
            <tbody>
              {rules.map((r) => (
                <tr key={r.id} className="border-t border-zinc-800">
                  <td className="p-1">{r.priority}</td>
                  <td className="p-1">{r.subject}</td>
                  <td className="p-1">{r.tool}</td>
                  <td className="p-1">
                    <span className="rounded bg-zinc-800 px-1.5 py-0.5">
                      {r.action}
                    </span>
                  </td>
                  <td className="p-1 text-zinc-500">{r.note}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      {status && <p className="text-xs text-zinc-400">{status}</p>}
    </div>
  );
}
