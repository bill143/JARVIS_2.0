"use client";

// Run 2: full memory governance UI — view/add/EDIT/pin/forget/export items,
// surface conflicts, and browse/delete the raw vector store (operator+).

import { useCallback, useEffect, useState } from "react";
import { delJson, getJson, postJson } from "@/lib/api";
import { useAuth } from "@/lib/auth";

type Item = { id: string; text: string; confidence: number; pinned: boolean; status: string; decayed_confidence: number; provenance: Record<string, unknown> };
type Conflict = Record<string, unknown> & { id?: string; reason?: string };
type VectorNamespace = { namespace: string; count: number };
type VectorRecord = { id: string; text: string; metadata: Record<string, unknown> };

export default function MemoryGovTab() {
  const { hasRole } = useAuth();
  const [items, setItems] = useState<Item[]>([]);
  const [conflicts, setConflicts] = useState<Conflict[]>([]);
  const [text, setText] = useState("");
  const [status, setStatus] = useState("");
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editText, setEditText] = useState("");
  const [namespaces, setNamespaces] = useState<VectorNamespace[]>([]);
  const [openNs, setOpenNs] = useState("");
  const [records, setRecords] = useState<VectorRecord[]>([]);

  const load = useCallback(async () => {
    const [r, c] = await Promise.all([
      getJson<{ items: Item[] }>("/memory/items"),
      getJson<{ conflicts: Conflict[] }>("/memory/conflicts"),
    ]);
    if (r.success) setItems(r.data.items);
    else setStatus(`${r.error.code}: ${r.error.message}`);
    if (c.success) setConflicts(c.data.conflicts);
  }, []);

  const loadVector = useCallback(async () => {
    if (!hasRole("operator")) return;
    const r = await getJson<{ namespaces: VectorNamespace[] }>("/memory/vector");
    if (r.success) setNamespaces(r.data.namespaces);
  }, [hasRole]);

  useEffect(() => {
    load();
    loadVector();
  }, [load, loadVector]);

  async function add() {
    if (!text.trim()) return;
    const r = await postJson("/memory/items", { text: text.trim() });
    setStatus(r.success ? "added" : `${r.error.code}: ${r.error.message}`);
    setText("");
    load();
  }

  async function act(id: string, path: string) {
    const r = await postJson(`/memory/items/${id}/${path}`, {});
    if (!r.success) setStatus(`${r.error.code}: ${r.error.message}`);
    load();
  }

  async function saveEdit() {
    if (!editingId || !editText.trim()) return;
    const r = await postJson(`/memory/items/${editingId}/edit`, { text: editText.trim() });
    setStatus(r.success ? "edited" : `${r.error.code}: ${r.error.message}`);
    setEditingId(null);
    setEditText("");
    load();
  }

  async function openNamespace(ns: string) {
    setOpenNs(ns);
    const r = await getJson<{ records: VectorRecord[] }>(`/memory/vector?namespace=${encodeURIComponent(ns)}`);
    if (r.success) setRecords(r.data.records);
    else setStatus(`${r.error.code}: ${r.error.message}`);
  }

  async function deleteVector(ns: string, id: string) {
    const r = await delJson(`/memory/vector/${encodeURIComponent(ns)}/${encodeURIComponent(id)}`);
    setStatus(r.success ? "vector record deleted" : `${r.error.code}: ${r.error.message}`);
    openNamespace(ns);
    loadVector();
  }

  async function exportData() {
    const r = await getJson<Record<string, unknown> & { count?: number; items?: unknown[] }>("/memory/export");
    if (!r.success) {
      setStatus(`${r.error.code}: ${r.error.message}`);
      return;
    }
    const blob = new Blob([JSON.stringify(r.data, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "memory-export.json";
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
    const n = r.data.count ?? r.data.items?.length ?? 0;
    setStatus(`exported ${n} item(s) — file downloaded`);
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
            {editingId === it.id ? (
              <div className="flex gap-2">
                <input value={editText} onChange={(e) => setEditText(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && saveEdit()}
                  aria-label="edit memory text" className="flex-1 rounded bg-zinc-900 px-2 py-1" />
                <button onClick={saveEdit} className="rounded bg-emerald-700 px-2 py-0.5 text-xs">Save</button>
                <button onClick={() => setEditingId(null)} className="rounded bg-zinc-700 px-2 py-0.5 text-xs">Cancel</button>
              </div>
            ) : (
              <div className="flex items-center justify-between">
                <span>{it.pinned && "📌 "}{it.text}</span>
                <span className="flex gap-2">
                  <button onClick={() => { setEditingId(it.id); setEditText(it.text); }}
                    className="rounded bg-zinc-700 px-2 py-0.5 text-xs">Edit</button>
                  <button onClick={() => act(it.id, `pin?pinned=${!it.pinned}`)} className="rounded bg-zinc-700 px-2 py-0.5 text-xs">
                    {it.pinned ? "Unpin" : "Pin"}
                  </button>
                  <button onClick={() => act(it.id, "forget")} className="rounded bg-red-700 px-2 py-0.5 text-xs">Forget</button>
                </span>
              </div>
            )}
            <p className="mt-1 text-xs text-zinc-500">
              confidence {it.confidence} · decayed {it.decayed_confidence} · {it.status} · why: {String(it.provenance?.why ?? "—")}
            </p>
          </div>
        ))}
      </div>

      <div className="rounded-lg border border-zinc-800 p-3">
        <h2 className="mb-1 text-xs uppercase tracking-wide text-zinc-500">Conflicts</h2>
        {conflicts.length === 0 ? (
          <p className="text-xs text-zinc-500">no conflicts detected</p>
        ) : (
          conflicts.map((c, i) => (
            <p key={String(c.id ?? i)} className="text-xs text-amber-400">
              ⚠ {JSON.stringify(c)}
            </p>
          ))
        )}
      </div>

      {hasRole("operator") && (
        <div className="rounded-lg border border-zinc-800 p-3">
          <h2 className="mb-1 text-xs uppercase tracking-wide text-zinc-500">Vector store</h2>
          {namespaces.length === 0 ? (
            <p className="text-xs text-zinc-500">no vector namespaces yet</p>
          ) : (
            <div className="flex flex-wrap gap-2">
              {namespaces.map((ns) => (
                <button key={ns.namespace} onClick={() => openNamespace(ns.namespace)}
                  aria-current={openNs === ns.namespace ? "true" : undefined}
                  className={`rounded px-2 py-1 text-xs ${openNs === ns.namespace ? "bg-emerald-700" : "bg-zinc-800 hover:bg-zinc-700"}`}>
                  {ns.namespace} ({ns.count})
                </button>
              ))}
            </div>
          )}
          {openNs && (
            <div className="mt-2 space-y-1">
              {records.length === 0 ? (
                <p className="text-xs text-zinc-500">namespace is empty</p>
              ) : (
                records.map((r) => (
                  <div key={r.id} className="flex items-center justify-between gap-2 text-xs">
                    <span className="truncate text-zinc-300" title={r.text}>{r.text.slice(0, 120)}</span>
                    <button onClick={() => deleteVector(openNs, r.id)}
                      className="shrink-0 rounded bg-red-800 px-2 py-0.5 hover:bg-red-700">Delete</button>
                  </div>
                ))
              )}
            </div>
          )}
        </div>
      )}

      {status && <p className="text-xs text-zinc-400">{status}</p>}
    </div>
  );
}
