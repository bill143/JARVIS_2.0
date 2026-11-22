"use client";

import { useEffect, useState } from "react";
import { getJson, postJson } from "@/lib/api";

type ExecData = { tool: string; status: string; output: unknown; duration_ms: number };
type AuditRow = { ts: string; tool: string; args_summary: string; status: string; duration_ms: number };
type EventRow = { ts: string; kind: string; payload: Record<string, unknown> };

export default function ToolsTab() {
  const [tools, setTools] = useState<string[]>([]);
  const [tool, setTool] = useState("python_exec");
  const [argsJson, setArgsJson] = useState('{"code": "print(2 + 2)"}');
  const [output, setOutput] = useState("");
  const [sessionId, setSessionId] = useState("default");
  const [audit, setAudit] = useState<AuditRow[]>([]);
  const [events, setEvents] = useState<EventRow[]>([]);

  useEffect(() => {
    getJson<{ tools: string[] }>("/health").then((r) => {
      if (r.success) setTools(r.data.tools);
    });
  }, []);

  async function run() {
    let args: unknown;
    try {
      args = JSON.parse(argsJson || "{}");
    } catch {
      setOutput("⚠ arguments must be valid JSON");
      return;
    }
    const resp = await postJson<ExecData>("/tools/execute", {
      tool,
      arguments: args,
      session_id: sessionId,
    });
    setOutput(
      resp.success
        ? JSON.stringify(resp.data, null, 2)
        : `⚠ ${resp.error.code}: ${resp.error.message} (requestId ${resp.error.requestId})`
    );
  }

  async function loadLogs() {
    const resp = await getJson<{ audit: AuditRow[]; events: EventRow[] }>(
      `/sessions/${encodeURIComponent(sessionId)}/logs`
    );
    if (resp.success) {
      setAudit(resp.data.audit);
      setEvents(resp.data.events);
    }
  }

  return (
    <div className="grid gap-4 md:grid-cols-2">
      <div className="rounded-lg border border-zinc-800 p-4">
        <h2 className="mb-2 text-sm font-medium text-emerald-400">Execute a tool</h2>
        <select
          value={tool}
          onChange={(e) => setTool(e.target.value)}
          aria-label="Tool"
          className="mb-2 w-full rounded-md bg-zinc-900 px-3 py-2 text-sm"
        >
          {(tools.length ? tools : [tool]).map((t) => (
            <option key={t}>{t}</option>
          ))}
        </select>
        <textarea
          value={argsJson}
          onChange={(e) => setArgsJson(e.target.value)}
          rows={4}
          aria-label="Tool arguments JSON"
          className="mb-2 w-full rounded-md bg-zinc-900 px-3 py-2 font-mono text-xs"
        />
        <button onClick={run} className="rounded-md bg-emerald-600 px-4 py-2 text-sm font-medium">
          Run tool
        </button>
        <pre className="mt-3 max-h-64 overflow-auto whitespace-pre-wrap rounded bg-zinc-900 p-2 text-xs">
          {output || "output appears here"}
        </pre>
      </div>

      <div className="rounded-lg border border-zinc-800 p-4">
        <h2 className="mb-2 text-sm font-medium text-emerald-400">Session logs</h2>
        <div className="mb-2 flex gap-2">
          <input
            value={sessionId}
            onChange={(e) => setSessionId(e.target.value)}
            aria-label="Session id"
            className="flex-1 rounded-md bg-zinc-900 px-3 py-2 text-sm"
          />
          <button onClick={loadLogs} className="rounded-md bg-zinc-700 px-4 py-2 text-sm">
            Load
          </button>
        </div>
        <h3 className="mt-2 text-xs uppercase text-zinc-500">Tool audit</h3>
        <ul className="max-h-40 space-y-1 overflow-auto text-xs">
          {audit.map((a, i) => (
            <li key={i} className="rounded bg-zinc-900 p-1.5">
              <span className={a.status === "ok" ? "text-emerald-400" : "text-red-400"}>{a.tool}</span>{" "}
              {a.duration_ms.toFixed(0)}ms · {a.args_summary.slice(0, 80)}
              <span className="block text-zinc-600">{a.ts}</span>
            </li>
          ))}
        </ul>
        <h3 className="mt-3 text-xs uppercase text-zinc-500">Events</h3>
        <ul className="max-h-40 space-y-1 overflow-auto text-xs">
          {events.map((e, i) => (
            <li key={i} className="rounded bg-zinc-900 p-1.5">
              <span className="text-emerald-400">{e.kind}</span> ·{" "}
              {JSON.stringify(e.payload).slice(0, 100)}
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
