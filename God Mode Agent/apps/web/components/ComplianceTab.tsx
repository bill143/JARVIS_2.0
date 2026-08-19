"use client";

import { useCallback, useEffect, useState } from "react";
import { getJson, postJson } from "@/lib/api";
import { useAuth } from "@/lib/auth";

type Policy = {
  data_class: string;
  retention_days: number;
  region: string;
  deletion_window_days: number;
  updated_at?: string;
};
type Status = {
  modes: Record<string, boolean>;
  retention: Policy[];
  audit: { ok: boolean; entries?: number };
};
type ModeDetail = { key: string; value: boolean; description: string };
type Meta = { modes_detail: ModeDetail[]; regions: string[] };
type ChangeReq = {
  id: string;
  created_at: string;
  requested_by: string;
  op: string;
  data_class: string;
  retention_days: number | null;
  region: string;
  deletion_window_days: number | null;
  reason: string;
  status: string;
  decided_by?: string | null;
};
type ExportRow = {
  id: string;
  created_at: string;
  requested_by: string;
  tenant: string;
  kind: string;
  record_count: number;
  digest: string;
};
type Report = {
  ok: boolean;
  entries: number;
  broken_at?: number;
  head?: string;
  by_category: Record<string, number>;
  categories: string[];
  first_ts: string | null;
  last_ts: string | null;
};

function downloadText(filename: string, content: string, fmt: string) {
  const mime = fmt === "csv" ? "text/csv" : "application/json";
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename || `evidence.${fmt}`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

const EMPTY_POLICY: Policy = { data_class: "", retention_days: 180, region: "global", deletion_window_days: 30 };

export default function ComplianceTab() {
  const { hasRole } = useAuth();
  const isAdmin = hasRole("admin");

  const [status, setStatus] = useState<Status | null>(null);
  const [meta, setMeta] = useState<Meta | null>(null);
  const [requests, setRequests] = useState<ChangeReq[]>([]);
  const [exportRows, setExportRows] = useState<ExportRow[]>([]);
  const [report, setReport] = useState<Report | null>(null);
  const [msg, setMsg] = useState("");

  const [modeChange, setModeChange] = useState<{ key: string; value: boolean } | null>(null);
  const [modeReason, setModeReason] = useState("");
  const [policyForm, setPolicyForm] = useState<{ mode: "add" | "edit"; policy: Policy } | null>(null);
  const [policyReason, setPolicyReason] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<Policy | null>(null);
  const [deleteReason, setDeleteReason] = useState("");

  const [fmt, setFmt] = useState("json");
  const [scope, setScope] = useState("all");
  const [start, setStart] = useState("");
  const [end, setEnd] = useState("");
  const [category, setCategory] = useState("");

  const refresh = useCallback(async () => {
    const [s, m, rq, ex] = await Promise.all([
      getJson<Status>("/compliance/status"),
      getJson<Meta>("/compliance/meta"),
      getJson<{ requests: ChangeReq[] }>("/compliance/retention/requests"),
      getJson<{ exports: ExportRow[] }>("/compliance/exports"),
    ]);
    if (s.success) setStatus(s.data);
    if (m.success) setMeta(m.data);
    if (rq.success) setRequests(rq.data.requests);
    if (ex.success) setExportRows(ex.data.exports);
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const regions = meta?.regions ?? ["global", "local"];
  const descFor = (key: string) => meta?.modes_detail.find((d) => d.key === key)?.description ?? "";
  const pending = requests.filter((r) => r.status === "pending");

  async function confirmModeChange() {
    if (!modeChange) return;
    if (modeReason.trim().length < 3) {
      setMsg("A reason (min 3 characters) is required to change a compliance mode.");
      return;
    }
    const r = await postJson("/compliance/mode", {
      key: modeChange.key,
      value: modeChange.value,
      reason: modeReason.trim(),
    });
    setMsg(r.success ? `Mode '${modeChange.key}' set to ${modeChange.value ? "on" : "off"}.` : `${r.error.code}: ${r.error.message}`);
    setModeChange(null);
    setModeReason("");
    refresh();
  }

  async function submitPolicyRequest() {
    if (!policyForm) return;
    const p = policyForm.policy;
    if (!p.data_class.trim()) {
      setMsg("Data class is required.");
      return;
    }
    if (policyReason.trim().length < 3) {
      setMsg("A reason (min 3 characters) is required.");
      return;
    }
    const r = await postJson("/compliance/retention/request", {
      op: "upsert",
      data_class: p.data_class.trim(),
      retention_days: Number(p.retention_days),
      region: p.region,
      deletion_window_days: Number(p.deletion_window_days),
      reason: policyReason.trim(),
    });
    setMsg(r.success ? `Change request submitted for '${p.data_class}' — awaiting approval.` : `${r.error.code}: ${r.error.message}`);
    setPolicyForm(null);
    setPolicyReason("");
    refresh();
  }

  async function submitDeleteRequest() {
    if (!deleteTarget) return;
    if (deleteReason.trim().length < 3) {
      setMsg("A reason (min 3 characters) is required.");
      return;
    }
    const r = await postJson("/compliance/retention/request", {
      op: "delete",
      data_class: deleteTarget.data_class,
      region: deleteTarget.region,
      reason: deleteReason.trim(),
    });
    setMsg(r.success ? `Delete request submitted for '${deleteTarget.data_class}' — awaiting approval.` : `${r.error.code}: ${r.error.message}`);
    setDeleteTarget(null);
    setDeleteReason("");
    refresh();
  }

  async function decide(reqId: string, decision: "approve" | "deny") {
    const r = await postJson(`/compliance/retention/requests/${reqId}/decide`, { decision, reason: "" });
    setMsg(r.success ? `Request ${decision === "approve" ? "approved & applied" : "denied"}.` : `${r.error.code}: ${r.error.message}`);
    refresh();
  }

  async function runValidation() {
    const r = await getJson<Report>("/compliance/audit/summary");
    if (r.success) {
      setReport(r.data);
      setMsg(r.data.ok ? `✓ audit chain intact (${r.data.entries} entries)` : `✗ TAMPERED at entry ${r.data.broken_at}`);
    } else {
      setMsg(`${r.error.code}: ${r.error.message}`);
    }
  }

  async function exportEvidence() {
    const qs = new URLSearchParams({ fmt, scope });
    if (start) qs.set("start", start);
    if (end) qs.set("end", end);
    if (category.trim()) qs.set("category", category.trim());
    const r = await postJson<{ id: string; record_count: number; filename: string; content: string }>(
      `/compliance/evidence/export?${qs.toString()}`,
      {},
    );
    if (r.success) {
      if (r.data.content) downloadText(r.data.filename, r.data.content, fmt);
      setMsg(`Exported ${r.data.record_count} records (id ${r.data.id}) — file downloaded.`);
      refresh();
    } else {
      setMsg(`${r.error.code}: ${r.error.message}`);
    }
  }

  async function redownload(id: string) {
    const r = await getJson<{ filename: string; format: string; content: string }>(
      `/compliance/evidence/exports/${id}/download`,
    );
    if (r.success) downloadText(r.data.filename, r.data.content, r.data.format);
    else setMsg(`${r.error.code}: ${r.error.message}`);
  }

  return (
    <div className="space-y-4">
      {!isAdmin && (
        <p className="rounded-md border border-amber-800 bg-amber-950/40 p-2 text-xs text-amber-300">
          You are viewing compliance in read-only mode. Editing modes and retention policies requires the admin role.
        </p>
      )}

      {status && (
        <>
          {/* Compliance modes */}
          <div className="rounded-lg border border-zinc-800 p-4 text-sm">
            <h2 className="mb-2 font-medium text-emerald-400">Compliance modes</h2>
            <div className="space-y-2">
              {Object.entries(status.modes).map(([k, v]) => (
                <div key={k} className="flex items-start gap-2">
                  <input
                    type="checkbox"
                    className="mt-1"
                    checked={v}
                    disabled={!isAdmin}
                    onChange={(e) => {
                      setModeReason("");
                      setModeChange({ key: k, value: e.target.checked });
                    }}
                    aria-label={k}
                  />
                  <div>
                    <div>
                      <span className="font-mono">{k}</span> —{" "}
                      <span className={v ? "text-emerald-400" : "text-zinc-500"}>{v ? "on" : "off"}</span>
                    </div>
                    <p className="text-xs text-zinc-500">{descFor(k)}</p>
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* Retention policies */}
          <div className="rounded-lg border border-zinc-800 p-4 text-sm">
            <div className="mb-2 flex items-center justify-between">
              <h2 className="font-medium text-emerald-400">Retention policies</h2>
              {isAdmin && (
                <button
                  onClick={() => {
                    setPolicyReason("");
                    setPolicyForm({ mode: "add", policy: { ...EMPTY_POLICY, region: regions[0] } });
                  }}
                  className="rounded-md bg-emerald-600 px-2 py-1 text-xs"
                >
                  + Add policy
                </button>
              )}
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="text-zinc-500">
                  <tr>
                    <th className="p-1">class</th>
                    <th className="p-1">days</th>
                    <th className="p-1">region</th>
                    <th className="p-1">deletion window</th>
                    {isAdmin && <th className="p-1">actions</th>}
                  </tr>
                </thead>
                <tbody>
                  {status.retention.map((p) => (
                    <tr key={p.data_class} className="border-t border-zinc-800">
                      <td className="p-1">{p.data_class}</td>
                      <td className="p-1">{p.retention_days}</td>
                      <td className="p-1">{p.region}</td>
                      <td className="p-1">{p.deletion_window_days}</td>
                      {isAdmin && (
                        <td className="p-1">
                          <button
                            onClick={() => {
                              setPolicyReason("");
                              setPolicyForm({ mode: "edit", policy: { ...p } });
                            }}
                            className="mr-2 text-emerald-400 hover:underline"
                          >
                            Edit
                          </button>
                          <button
                            onClick={() => {
                              setDeleteReason("");
                              setDeleteTarget(p);
                            }}
                            className="text-red-400 hover:underline"
                          >
                            Delete
                          </button>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="mt-2 text-xs text-zinc-500">
              Edits, additions and deletions are submitted as change requests and take effect only after admin approval.
            </p>
          </div>

          {/* Pending change requests */}
          {isAdmin && pending.length > 0 && (
            <div className="rounded-lg border border-amber-800 p-4 text-sm">
              <h2 className="mb-2 font-medium text-amber-400">Pending retention change requests</h2>
              <div className="space-y-2">
                {pending.map((r) => (
                  <div key={r.id} className="flex items-center justify-between gap-2 border-t border-zinc-800 pt-2 text-xs">
                    <div>
                      <span className="font-mono">{r.op}</span> <b>{r.data_class}</b>{" "}
                      {r.op === "upsert" && (
                        <span className="text-zinc-400">
                          → {r.retention_days}d / {r.region} / window {r.deletion_window_days}d
                        </span>
                      )}
                      <div className="text-zinc-500">
                        by {r.requested_by} — “{r.reason}”
                      </div>
                    </div>
                    <div className="shrink-0">
                      <button onClick={() => decide(r.id, "approve")} className="mr-2 rounded bg-emerald-600 px-2 py-1">
                        Approve
                      </button>
                      <button onClick={() => decide(r.id, "deny")} className="rounded bg-zinc-700 px-2 py-1">
                        Deny
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Audit validation */}
          <div className="rounded-lg border border-zinc-800 p-4 text-sm">
            <div className="mb-2 flex items-center justify-between">
              <h2 className="font-medium text-emerald-400">Audit chain</h2>
              <button onClick={runValidation} className="rounded-md bg-emerald-600 px-3 py-1.5 text-xs">
                Validate &amp; report
              </button>
            </div>
            {report && (
              <div className="text-xs text-zinc-300">
                <p>
                  Integrity:{" "}
                  <span className={report.ok ? "text-emerald-400" : "text-red-400"}>
                    {report.ok ? "✓ intact" : `✗ tampered at entry ${report.broken_at}`}
                  </span>{" "}
                  · {report.entries} entries · {report.first_ts?.slice(0, 19) ?? "—"} → {report.last_ts?.slice(0, 19) ?? "—"}
                </p>
                <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1">
                  {Object.entries(report.by_category).map(([c, n]) => (
                    <span key={c} className="text-zinc-400">
                      {c}: <b className="text-zinc-200">{n}</b>
                    </span>
                  ))}
                </div>
              </div>
            )}
          </div>

          {/* Evidence export */}
          {isAdmin && (
            <div className="rounded-lg border border-zinc-800 p-4 text-sm">
              <h2 className="mb-2 font-medium text-emerald-400">Evidence export</h2>
              <div className="flex flex-wrap items-end gap-2 text-xs">
                <label className="flex flex-col">
                  format
                  <select value={fmt} onChange={(e) => setFmt(e.target.value)} className="rounded bg-zinc-800 p-1">
                    <option value="json">JSON</option>
                    <option value="csv">CSV</option>
                  </select>
                </label>
                <label className="flex flex-col">
                  scope
                  <select value={scope} onChange={(e) => setScope(e.target.value)} className="rounded bg-zinc-800 p-1">
                    <option value="all">all tenants</option>
                    <option value="tenant">my tenant</option>
                  </select>
                </label>
                <label className="flex flex-col">
                  from
                  <input type="date" value={start} onChange={(e) => setStart(e.target.value)} className="rounded bg-zinc-800 p-1" />
                </label>
                <label className="flex flex-col">
                  to
                  <input type="date" value={end} onChange={(e) => setEnd(e.target.value)} className="rounded bg-zinc-800 p-1" />
                </label>
                <label className="flex flex-col">
                  category
                  <input
                    value={category}
                    onChange={(e) => setCategory(e.target.value)}
                    placeholder="(all)"
                    className="w-28 rounded bg-zinc-800 p-1"
                  />
                </label>
                <button onClick={exportEvidence} className="rounded-md bg-emerald-600 px-3 py-1.5">
                  Export &amp; download
                </button>
              </div>

              {exportRows.length > 0 && (
                <div className="mt-3 overflow-x-auto">
                  <h3 className="mb-1 text-xs text-zinc-500">Export history</h3>
                  <table className="w-full text-left text-xs">
                    <thead className="text-zinc-500">
                      <tr>
                        <th className="p-1">when</th>
                        <th className="p-1">by</th>
                        <th className="p-1">fmt</th>
                        <th className="p-1">records</th>
                        <th className="p-1">digest</th>
                        <th className="p-1"></th>
                      </tr>
                    </thead>
                    <tbody>
                      {exportRows.map((x) => (
                        <tr key={x.id} className="border-t border-zinc-800">
                          <td className="p-1">{x.created_at.slice(0, 19)}</td>
                          <td className="p-1">{x.requested_by}</td>
                          <td className="p-1">{x.kind}</td>
                          <td className="p-1">{x.record_count}</td>
                          <td className="p-1 font-mono">{x.digest.slice(0, 10)}…</td>
                          <td className="p-1">
                            <button onClick={() => redownload(x.id)} className="text-emerald-400 hover:underline">
                              Download
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}
        </>
      )}

      {msg && <p className="text-xs text-zinc-400">{msg}</p>}

      {/* Mode-change confirmation modal */}
      {modeChange && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
          <div className="w-full max-w-md rounded-lg border border-zinc-700 bg-zinc-900 p-4 text-sm">
            <h3 className="mb-2 font-medium text-emerald-400">Confirm compliance mode change</h3>
            <p className="text-xs text-zinc-400">
              Set <span className="font-mono">{modeChange.key}</span> to{" "}
              <b>{modeChange.value ? "on" : "off"}</b>. This is recorded in the audit log.
            </p>
            <p className="mt-1 text-xs text-zinc-500">{descFor(modeChange.key)}</p>
            <label className="mt-3 block text-xs">
              Reason (required)
              <textarea
                value={modeReason}
                onChange={(e) => setModeReason(e.target.value)}
                rows={2}
                className="mt-1 w-full rounded bg-zinc-800 p-2"
                placeholder="Why is this change being made?"
              />
            </label>
            <div className="mt-3 flex justify-end gap-2">
              <button onClick={() => { setModeChange(null); setModeReason(""); }} className="rounded bg-zinc-700 px-3 py-1.5 text-xs">
                Cancel
              </button>
              <button onClick={confirmModeChange} className="rounded bg-emerald-600 px-3 py-1.5 text-xs">
                Confirm change
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Retention add/edit modal */}
      {policyForm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
          <div className="w-full max-w-md rounded-lg border border-zinc-700 bg-zinc-900 p-4 text-sm">
            <h3 className="mb-2 font-medium text-emerald-400">
              {policyForm.mode === "add" ? "Add retention policy" : `Edit '${policyForm.policy.data_class}'`}
            </h3>
            <div className="space-y-2 text-xs">
              <label className="block">
                data class
                <input
                  value={policyForm.policy.data_class}
                  disabled={policyForm.mode === "edit"}
                  onChange={(e) => setPolicyForm({ ...policyForm, policy: { ...policyForm.policy, data_class: e.target.value } })}
                  className="mt-1 w-full rounded bg-zinc-800 p-2 disabled:opacity-60"
                />
              </label>
              <label className="block">
                retention days
                <input
                  type="number"
                  value={policyForm.policy.retention_days}
                  onChange={(e) => setPolicyForm({ ...policyForm, policy: { ...policyForm.policy, retention_days: Number(e.target.value) } })}
                  className="mt-1 w-full rounded bg-zinc-800 p-2"
                />
              </label>
              <label className="block">
                region
                <select
                  value={policyForm.policy.region}
                  onChange={(e) => setPolicyForm({ ...policyForm, policy: { ...policyForm.policy, region: e.target.value } })}
                  className="mt-1 w-full rounded bg-zinc-800 p-2"
                >
                  {regions.map((rg) => (
                    <option key={rg} value={rg}>
                      {rg}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block">
                deletion window (days)
                <input
                  type="number"
                  value={policyForm.policy.deletion_window_days}
                  onChange={(e) => setPolicyForm({ ...policyForm, policy: { ...policyForm.policy, deletion_window_days: Number(e.target.value) } })}
                  className="mt-1 w-full rounded bg-zinc-800 p-2"
                />
              </label>
              <label className="block">
                reason (required)
                <input
                  value={policyReason}
                  onChange={(e) => setPolicyReason(e.target.value)}
                  className="mt-1 w-full rounded bg-zinc-800 p-2"
                  placeholder="Why this change?"
                />
              </label>
            </div>
            <div className="mt-3 flex justify-end gap-2">
              <button onClick={() => { setPolicyForm(null); setPolicyReason(""); }} className="rounded bg-zinc-700 px-3 py-1.5 text-xs">
                Cancel
              </button>
              <button onClick={submitPolicyRequest} className="rounded bg-emerald-600 px-3 py-1.5 text-xs">
                Submit request
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Retention delete modal */}
      {deleteTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
          <div className="w-full max-w-md rounded-lg border border-zinc-700 bg-zinc-900 p-4 text-sm">
            <h3 className="mb-2 font-medium text-red-400">Delete retention policy</h3>
            <p className="text-xs text-zinc-400">
              Submit a request to delete <b>{deleteTarget.data_class}</b>. Takes effect after admin approval.
            </p>
            <label className="mt-3 block text-xs">
              reason (required)
              <input
                value={deleteReason}
                onChange={(e) => setDeleteReason(e.target.value)}
                className="mt-1 w-full rounded bg-zinc-800 p-2"
                placeholder="Why remove this policy?"
              />
            </label>
            <div className="mt-3 flex justify-end gap-2">
              <button onClick={() => { setDeleteTarget(null); setDeleteReason(""); }} className="rounded bg-zinc-700 px-3 py-1.5 text-xs">
                Cancel
              </button>
              <button onClick={submitDeleteRequest} className="rounded bg-red-600 px-3 py-1.5 text-xs">
                Submit delete request
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
