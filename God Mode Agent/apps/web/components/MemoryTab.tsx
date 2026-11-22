"use client";

import { useState } from "react";
import { getJson, postJson } from "@/lib/api";

type Hit = { id: string; text: string; score: number; metadata: Record<string, unknown> };

export default function MemoryTab() {
  const [userId, setUserId] = useState("default");
  const [upsertText, setUpsertText] = useState("");
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<Hit[]>([]);
  const [status, setStatus] = useState("");

  async function upsert() {
    if (!upsertText.trim()) return;
    const resp = await postJson<{ id: string; namespace: string }>("/memory/upsert", {
      text: upsertText.trim(),
      user_id: userId,
    });
    setStatus(
      resp.success
        ? `✓ saved as ${resp.data.id} in namespace ${resp.data.namespace}`
        : `⚠ ${resp.error.code}: ${resp.error.message}`
    );
    if (resp.success) setUpsertText("");
  }

  async function search() {
    if (!query.trim()) return;
    const resp = await getJson<{ hits: Hit[]; backend: string }>(
      `/memory/search?q=${encodeURIComponent(query)}&user_id=${encodeURIComponent(userId)}&k=8`
    );
    if (resp.success) {
      setHits(resp.data.hits);
      setStatus(`backend: ${resp.data.backend}, ${resp.data.hits.length} hit(s)`);
    } else setStatus(`⚠ ${resp.error.code}: ${resp.error.message}`);
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <label htmlFor="mem-user" className="text-sm text-zinc-400">User namespace</label>
        <input
          id="mem-user"
          value={userId}
          onChange={(e) => setUserId(e.target.value)}
          className="rounded-md bg-zinc-900 px-3 py-1.5 text-sm outline-none ring-emerald-500 focus:ring-2"
        />
      </div>

      <div className="rounded-lg border border-zinc-800 p-4">
        <h2 className="mb-2 text-sm font-medium text-emerald-400">Remember something</h2>
        <div className="flex gap-2">
          <input
            value={upsertText}
            onChange={(e) => setUpsertText(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && upsert()}
            placeholder="e.g. My favorite build tool is uv"
            aria-label="Memory text"
            className="flex-1 rounded-md bg-zinc-900 px-3 py-2 text-sm outline-none ring-emerald-500 focus:ring-2"
          />
          <button onClick={upsert} className="rounded-md bg-emerald-600 px-4 py-2 text-sm font-medium">
            Save
          </button>
        </div>
      </div>

      <div className="rounded-lg border border-zinc-800 p-4">
        <h2 className="mb-2 text-sm font-medium text-emerald-400">Search memory</h2>
        <div className="flex gap-2">
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && search()}
            placeholder="semantic query"
            aria-label="Memory search query"
            className="flex-1 rounded-md bg-zinc-900 px-3 py-2 text-sm outline-none ring-emerald-500 focus:ring-2"
          />
          <button onClick={search} className="rounded-md bg-zinc-700 px-4 py-2 text-sm">
            Search
          </button>
        </div>
        <ul className="mt-3 space-y-2">
          {hits.map((h) => (
            <li key={h.id} className="rounded bg-zinc-900 p-2 text-sm">
              <span className="mr-2 text-xs text-emerald-400">{h.score.toFixed(3)}</span>
              {h.text}
            </li>
          ))}
        </ul>
      </div>

      {status && <p className="text-xs text-zinc-400">{status}</p>}
    </div>
  );
}
