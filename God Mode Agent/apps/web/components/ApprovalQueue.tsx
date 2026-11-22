"use client";

import { useEffect, useState } from "react";
import { getJson, postJson } from "@/lib/api";

type Approval = {
  id: string;
  requester: string;
  tool: string;
  arguments: Record<string, unknown>;
  reason: string;
  status: string;
  result?: { status: string; output: unknown } | null;
};

export default function ApprovalQueue() {
  const [approvals, setApprovals] = useState<Approval[]>([]);
  const [status, setStatus] = useState("");

  async function load() {
    const resp = await getJson<{ approvals: Approval[] }>("/approvals");
    if (resp.success) setApprovals(resp.data.approvals);
    else setStatus(`${resp.error.code}: ${resp.error.message}`);
  }

  useEffect(() => {
    load();
  }, []);

  async function decide(id: string, decision: "approved" | "denied") {
    const resp = await postJson<{
      executed: boolean;
      result?: { output: unknown };
    }>(`/approvals/${id}/decide`, { decision });
    if (resp.success) {
      setStatus(
        decision === "approved" && resp.data.executed
          ? `approved & executed: ${JSON.stringify(resp.data.result?.output).slice(0, 120)}`
          : `marked ${decision}`,
      );
    } else {
      setStatus(`${resp.error.code}: ${resp.error.message}`);
    }
    load();
  }

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-medium text-emerald-400">Approval queue</h2>
        <button
          onClick={load}
          className="rounded-md bg-zinc-700 px-3 py-1 text-xs"
        >
          Refresh
        </button>
      </div>
      {approvals.length === 0 && (
        <p className="text-sm text-zinc-500">No approvals.</p>
      )}
      {approvals.map((a) => (
        <div
          key={a.id}
          className="rounded-lg border border-zinc-800 p-3 text-sm"
        >
          <div className="flex items-center justify-between">
            <span>
              <span className="font-medium text-amber-400">{a.tool}</span>{" "}
              requested by {a.requester}
              <span className="ml-2 rounded bg-zinc-800 px-1.5 py-0.5 text-xs">
                {a.status}
              </span>
            </span>
            {a.status === "pending" && (
              <span className="flex gap-2">
                <button
                  onClick={() => decide(a.id, "approved")}
                  className="rounded-md bg-emerald-600 px-3 py-1 text-xs"
                >
                  Approve
                </button>
                <button
                  onClick={() => decide(a.id, "denied")}
                  className="rounded-md bg-red-600 px-3 py-1 text-xs"
                >
                  Deny
                </button>
              </span>
            )}
          </div>
          <p className="mt-1 text-xs text-zinc-500">{a.reason}</p>
          <pre className="mt-1 overflow-x-auto rounded bg-zinc-900 p-1.5 text-[11px]">
            {JSON.stringify(a.arguments)}
          </pre>
        </div>
      ))}
      {status && <p className="text-xs text-zinc-400">{status}</p>}
    </div>
  );
}
