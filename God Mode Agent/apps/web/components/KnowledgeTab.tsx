"use client";

import { useState } from "react";
import { postJson } from "@/lib/api";

type Citation = { marker: string; source: string; snippet: string };
type Answer = { answer: string; citations: Citation[]; confidence: number; segments: { text: string; citation: string; confidence: number }[] };

export default function KnowledgeTab() {
  const [source, setSource] = useState("kb-note-1");
  const [content, setContent] = useState("Quantum computing uses qubits and superposition to perform parallel computation.");
  const [query, setQuery] = useState("how do qubits work");
  const [ans, setAns] = useState<Answer | null>(null);
  const [status, setStatus] = useState("");

  async function ingest() {
    const r = await postJson<{ chunks: number; deduplicated: boolean }>("/rag/ingest",
      { source, title: source, content, sensitivity: "internal", strategy: "semantic" });
    setStatus(r.success ? `ingested ${r.data.chunks} chunk(s)${r.data.deduplicated ? " (dedup)" : ""}` : `${r.error.code}: ${r.error.message}`);
  }
  async function ask() {
    const r = await postJson<Answer>("/rag/query", { query });
    if (r.success) setAns(r.data); else setStatus(`${r.error.code}: ${r.error.message}`);
  }

  return (
    <div className="space-y-4">
      <div className="rounded-lg border border-zinc-800 p-4">
        <h2 className="mb-2 text-sm font-medium text-emerald-400">Ingest knowledge (operator+)</h2>
        <input value={source} onChange={(e) => setSource(e.target.value)} aria-label="source"
          className="mb-2 w-full rounded-md bg-zinc-900 px-3 py-2 text-sm" />
        <textarea value={content} onChange={(e) => setContent(e.target.value)} rows={3} aria-label="content"
          className="mb-2 w-full rounded-md bg-zinc-900 px-3 py-2 text-sm" />
        <button onClick={ingest} className="rounded-md bg-emerald-600 px-3 py-1.5 text-sm font-medium">Ingest</button>
      </div>

      <div className="rounded-lg border border-zinc-800 p-4">
        <h2 className="mb-2 text-sm font-medium text-emerald-400">Ask (hybrid RAG + citations)</h2>
        <div className="flex gap-2">
          <input value={query} onChange={(e) => setQuery(e.target.value)} onKeyDown={(e) => e.key === "Enter" && ask()}
            aria-label="query" className="flex-1 rounded-md bg-zinc-900 px-3 py-2 text-sm" />
          <button onClick={ask} className="rounded-md bg-zinc-700 px-4 py-2 text-sm">Ask</button>
        </div>
        {ans && (
          <div className="mt-3 space-y-2 text-sm">
            <p className="rounded bg-zinc-900 p-2">{ans.answer || "(no grounded answer)"}</p>
            <p className="text-xs text-zinc-500">confidence: {ans.confidence}</p>
            {ans.citations.map((c) => (
              <div key={c.marker} className="rounded border border-zinc-800 p-2 text-xs">
                <span className="text-emerald-400">{c.marker}</span> <span className="text-zinc-500">{c.source}</span>
                <p className="mt-1 text-zinc-400">{c.snippet}</p>
              </div>
            ))}
          </div>
        )}
      </div>
      {status && <p className="text-xs text-zinc-400">{status}</p>}
    </div>
  );
}
