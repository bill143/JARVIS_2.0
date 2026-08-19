"use client";

import { useState } from "react";
import { getJson, postJson } from "@/lib/api";

type Msg = { sender: string; role: string; round: number; content: string; meta: Record<string, unknown> };
type Result = { session_id: string; decision: string; arbitration: string };

export default function AgentsTab() {
  const [goal, setGoal] = useState("investigate quantum computing");
  const [arbitration, setArbitration] = useState("critic-override");
  const [result, setResult] = useState<Result | null>(null);
  const [messages, setMessages] = useState<Msg[]>([]);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  async function run() {
    setBusy(true);
    setErr("");
    const r = await postJson<Result>("/agents/run", { goal, arbitration });
    if (r.success) {
      setResult(r.data);
      await loadSession(r.data.session_id);
    } else {
      setErr(`${r.error.code}: ${r.error.message}`);
    }
    setBusy(false);
  }

  async function loadSession(id: string) {
    const r = await getJson<{ messages: Msg[] }>(`/agents/sessions/${id}`);
    if (r.success) setMessages(r.data.messages);
  }

  const roleColor: Record<string, string> = {
    coordinator: "text-emerald-400", researcher: "text-sky-400", coder: "text-amber-400",
    verifier: "text-fuchsia-400", memory_steward: "text-lime-400",
  };

  return (
    <div className="space-y-4">
      <div className="rounded-lg border border-zinc-800 p-4">
        <div className="flex flex-wrap gap-2">
          <input value={goal} onChange={(e) => setGoal(e.target.value)} aria-label="goal"
            className="flex-1 rounded-md bg-zinc-900 px-3 py-2 text-sm" />
          <select value={arbitration} onChange={(e) => setArbitration(e.target.value)} aria-label="arbitration"
            className="rounded-md bg-zinc-900 px-2 py-1.5 text-sm">
            {["critic-override", "majority"].map((a) => <option key={a}>{a}</option>)}
          </select>
          <button onClick={run} disabled={busy} className="rounded-md bg-emerald-600 px-4 py-2 text-sm font-medium disabled:opacity-50">
            {busy ? "Running…" : "Run agents"}
          </button>
        </div>
        {err && <p className="mt-2 text-xs text-red-400">{err}</p>}
      </div>

      {result && (
        <div className="rounded-lg border border-zinc-800 p-4 text-sm">
          decision: <span className="text-emerald-400">{result.decision}</span> · {result.arbitration}
        </div>
      )}

      {messages.length > 0 && (
        <div className="space-y-2 rounded-lg border border-zinc-800 p-4">
          <h2 className="text-sm font-medium text-emerald-400">Agent timeline</h2>
          {messages.map((m, i) => (
            <div key={i} className="rounded bg-zinc-900 p-2 text-sm">
              <span className={`font-medium ${roleColor[m.sender] ?? "text-zinc-300"}`}>{m.sender}</span>
              <span className="ml-2 text-xs text-zinc-500">round {m.round}</span>
              <p className="mt-1 whitespace-pre-wrap text-zinc-300">{m.content.slice(0, 300)}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
