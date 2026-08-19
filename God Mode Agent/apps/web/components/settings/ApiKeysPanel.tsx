"use client";

import { useCallback, useEffect, useState } from "react";
import { getJson, postJson } from "@/lib/api";

type KeyRow = {
  id: string;
  name: string;
  prefix: string;
  role: string;
  tenant: string;
  scopes: string;
  created_at: string;
  revoked: boolean;
};

export default function ApiKeysPanel() {
  const [keys, setKeys] = useState<KeyRow[]>([]);
  const [error, setError] = useState("");
  const [freshKey, setFreshKey] = useState<{ name: string; key: string } | null>(null);
  const [form, setForm] = useState({ name: "", role: "user" });
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const r = await getJson<{ keys: KeyRow[] }>("/apikeys");
    if (r.success) setKeys(r.data.keys);
    else setError(`${r.error.code}: ${r.error.message}`);
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function createKey() {
    if (!form.name.trim()) {
      setError("key name is required");
      return;
    }
    setBusy(true);
    setError("");
    const r = await postJson<{ api_key: string }>("/apikeys", { name: form.name.trim(), role: form.role });
    setBusy(false);
    if (r.success) {
      setFreshKey({ name: form.name.trim(), key: r.data.api_key });
      setForm({ name: "", role: "user" });
      await load();
    } else {
      setError(`${r.error.code}: ${r.error.message}`);
    }
  }

  async function revoke(k: KeyRow) {
    setBusy(true);
    setError("");
    const r = await postJson(`/apikeys/${k.id}/revoke`, {});
    setBusy(false);
    if (r.success) await load();
    else setError(`${r.error.code}: ${r.error.message}`);
  }

  return (
    <div className="rounded-lg border border-zinc-800 p-4">
      <h2 className="mb-2 font-medium text-emerald-400">API keys</h2>
      {error && <p className="mb-2 text-xs text-red-400">⚠ {error}</p>}
      {freshKey && (
        <div className="mb-3 rounded border border-amber-700 bg-amber-950/40 p-2 text-xs">
          Key <b>{freshKey.name}</b> created — copy it now, it is shown only once:{" "}
          <code className="select-all break-all text-amber-300">{freshKey.key}</code>
          <button className="ml-3 text-zinc-400 hover:text-zinc-200" onClick={() => setFreshKey(null)}>
            dismiss
          </button>
        </div>
      )}

      <table className="w-full text-left text-xs">
        <thead className="text-zinc-500">
          <tr>
            <th className="py-1 pr-2">Name</th>
            <th className="py-1 pr-2">Prefix</th>
            <th className="py-1 pr-2">Role</th>
            <th className="py-1 pr-2">Status</th>
            <th className="py-1">Actions</th>
          </tr>
        </thead>
        <tbody>
          {keys.length === 0 && (
            <tr>
              <td colSpan={5} className="py-2 text-zinc-500">
                No API keys yet.
              </td>
            </tr>
          )}
          {keys.map((k) => (
            <tr key={k.id} className="border-t border-zinc-800/60">
              <td className="py-1.5 pr-2">{k.name}</td>
              <td className="py-1.5 pr-2 font-mono">{k.prefix}…</td>
              <td className="py-1.5 pr-2">{k.role}</td>
              <td className={`py-1.5 pr-2 ${k.revoked ? "text-red-400" : "text-emerald-400"}`}>
                {k.revoked ? "revoked" : "active"}
              </td>
              <td className="py-1.5">
                {!k.revoked && (
                  <button
                    className="text-zinc-400 hover:text-red-300 disabled:opacity-30"
                    disabled={busy}
                    onClick={() => revoke(k)}
                  >
                    revoke
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-zinc-800/60 pt-3 text-xs">
        <input
          aria-label="api key name"
          className="rounded bg-zinc-800 px-2 py-1"
          placeholder="key name (e.g. ci-bot)"
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
        />
        <select
          aria-label="api key role"
          className="rounded bg-zinc-800 px-1 py-1"
          value={form.role}
          onChange={(e) => setForm({ ...form, role: e.target.value })}
        >
          {["readonly", "user", "operator"].map((r) => (
            <option key={r}>{r}</option>
          ))}
        </select>
        <button
          className="rounded bg-emerald-700 px-3 py-1 font-medium hover:bg-emerald-600 disabled:opacity-40"
          disabled={busy}
          onClick={createKey}
        >
          Create key
        </button>
      </div>
    </div>
  );
}
