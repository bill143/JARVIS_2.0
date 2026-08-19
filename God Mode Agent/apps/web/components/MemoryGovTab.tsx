"use client";

import { useEffect, useState } from "react";
import { getJson, postJson } from "@/lib/api";

type Item = { id: string; text: string; confidence: number; pinned: boolean; status: string; decayed_confidence: number; provenance: Record<string, unknown> };

export default function MemoryGovTab() {
  const [items, setItems] = useState<Item[]>([]);
  const [text, setText] = useState("");
  const [status, setStatus] = useState("");

  async function load() {
    const r = await getJson<{ items: Item[] }>("/memory/items");
    if (r.success) setItems(r.data.items);
  }
  useEffect(() => { load(); }, []);

  async function add() {
    if (!text.trim()) return;
    const r = await postJson("/memory/items", { text: text.trim() });
    setStatus(r.success ? "added" : `${r.error.code}: ${r.error.message}`);
    setText("");
    load();
  }
  async function act(id: string, path: string) {
    await postJson(`/memory/items/${id}/${path}`, {});
    load();
  }
  async function exportData() {
    const r = await getJson<{ count: number }>("/memory/export");
    if (r.success) setStatus(`exported ${r.data.count} item(s)`);
  }

  return (
    <div className="space-y-4">
      <div className="flex gap-2">
        <input value={text} onChange={(e) => setText(e.target.value)} onKeyDown={(e) => e.key === "Enter" && add()}
          placeholder="Add a memory…" aria-label="memory text"
          className="flex-1 rounded-md bg-zinc-900 px-3 py-2 text-sm" />
        <button onClick={add} className="rounded-md bg-emerald-600 px-4 py-2 text-sm font-medium">Add</button>
        <button onClick={exportData} className="rounded-md bg-zinc-700 px-4 py-2 text-sm">Export</button>
      </div>
      <div className="space-y-2">
        {items.length === 0 && <p className="text-sm text-zinc-500">No governed memories yet.</p>}
        {items.map((it) => (
          <div key={it.id} className="rounded-lg border border-zinc-800 p-3 text-sm">
            <div className="flex items-center justify-between">
              <span>{it.pinned && "📌 "}{it.text}</span>
              <span className="flex gap-2">
                <button onClick={() => act(it.id, `pin?pinned=${!it.pinned}`)} className="rounded bg-zinc-700 px-2 py-0.5 text-xs">
                  {it.pinned ? "Unpin" : "Pin"}
                </button>
                <button onClick={() => act(it.id, "forget")} className="rounded bg-red-700 px-2 py-0.5 text-xs">Forget</button>
              </span>
            </div>
            <p className="mt-1 text-xs text-zinc-500">
              confidence {it.confidence} · decayed {it.decayed_confidence} · {it.status} · why: {String(it.provenance?.why ?? "—")}
            </p>
          </div>
        ))}
      </div>
      {status && <p className="text-xs text-zinc-400">{status}</p>}
    </div>
  );
}
