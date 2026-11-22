"use client";

import { useCallback, useEffect, useState } from "react";
import { getJson, postJson, type Envelope } from "@/lib/api";
import { useAuth } from "@/lib/auth";

type UserRow = {
  id: string;
  username: string;
  email: string;
  role: string;
  tenant: string;
  disabled: boolean;
  created_at: string;
};

const ROLES = ["readonly", "user", "operator", "admin"];

export default function UsersPanel() {
  const { user: me } = useAuth();
  const [users, setUsers] = useState<UserRow[]>([]);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [tempPw, setTempPw] = useState<{ username: string; password: string } | null>(null);
  const [newUser, setNewUser] = useState({ username: "", password: "", role: "user" });
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const r = await getJson<{ users: UserRow[] }>("/users");
    if (r.success) setUsers(r.data.users);
    else setError(`${r.error.code}: ${r.error.message}`);
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function run(action: () => Promise<Envelope<unknown>>, okMsg: string) {
    setBusy(true);
    setError("");
    setNotice("");
    const r = await action();
    setBusy(false);
    if (r.success) {
      setNotice(okMsg);
      await load();
    } else {
      setError(`${r.error.code}: ${r.error.message}`);
    }
  }

  async function createUser() {
    if (!newUser.username || newUser.password.length < 8) {
      setError("username required; password must be at least 8 characters");
      return;
    }
    await run(
      () => postJson("/auth/register", newUser),
      `user '${newUser.username}' created as ${newUser.role}`,
    );
    setNewUser({ username: "", password: "", role: "user" });
  }

  async function resetPassword(u: UserRow) {
    setBusy(true);
    setError("");
    const r = await postJson<{ temporary_password: string }>(`/users/${u.id}/reset-password`, {});
    setBusy(false);
    if (r.success) setTempPw({ username: u.username, password: r.data.temporary_password });
    else setError(`${r.error.code}: ${r.error.message}`);
  }

  return (
    <div className="rounded-lg border border-zinc-800 p-4">
      <h2 className="mb-2 font-medium text-emerald-400">Users &amp; roles</h2>
      {error && <p className="mb-2 text-xs text-red-400">⚠ {error}</p>}
      {notice && <p className="mb-2 text-xs text-emerald-400">✓ {notice}</p>}
      {tempPw && (
        <div className="mb-3 rounded border border-amber-700 bg-amber-950/40 p-2 text-xs">
          Temporary password for <b>{tempPw.username}</b> (shown once):{" "}
          <code className="select-all text-amber-300">{tempPw.password}</code>
          <button className="ml-3 text-zinc-400 hover:text-zinc-200" onClick={() => setTempPw(null)}>
            dismiss
          </button>
        </div>
      )}

      <table className="w-full text-left text-xs">
        <thead className="text-zinc-500">
          <tr>
            <th className="py-1 pr-2">User</th>
            <th className="py-1 pr-2">Role</th>
            <th className="py-1 pr-2">Status</th>
            <th className="py-1">Actions</th>
          </tr>
        </thead>
        <tbody>
          {users.map((u) => {
            const isSelf = me?.username === u.username;
            return (
              <tr key={u.id} className="border-t border-zinc-800/60">
                <td className="py-1.5 pr-2">
                  {u.username}
                  {isSelf && <span className="ml-1 text-zinc-500">(you)</span>}
                </td>
                <td className="py-1.5 pr-2">
                  <select
                    aria-label={`role for ${u.username}`}
                    className="rounded bg-zinc-800 px-1 py-0.5"
                    value={u.role}
                    disabled={busy}
                    onChange={(e) =>
                      run(() => postJson(`/users/${u.id}/role`, { role: e.target.value }),
                        `role of '${u.username}' set to ${e.target.value}`)
                    }
                  >
                    {ROLES.map((r) => (
                      <option key={r}>{r}</option>
                    ))}
                  </select>
                </td>
                <td className={`py-1.5 pr-2 ${u.disabled ? "text-red-400" : "text-emerald-400"}`}>
                  {u.disabled ? "disabled" : "active"}
                </td>
                <td className="space-x-2 py-1.5">
                  <button
                    className="text-zinc-400 hover:text-zinc-100 disabled:opacity-30"
                    disabled={busy || isSelf}
                    title={isSelf ? "you cannot disable your own account" : undefined}
                    onClick={() =>
                      run(() => postJson(`/users/${u.id}/${u.disabled ? "enable" : "disable"}`, {}),
                        `'${u.username}' ${u.disabled ? "enabled" : "disabled"}`)
                    }
                  >
                    {u.disabled ? "enable" : "disable"}
                  </button>
                  <button
                    className="text-zinc-400 hover:text-zinc-100 disabled:opacity-30"
                    disabled={busy}
                    onClick={() => resetPassword(u)}
                  >
                    reset password
                  </button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>

      <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-zinc-800/60 pt-3 text-xs">
        <input
          aria-label="new username"
          className="rounded bg-zinc-800 px-2 py-1"
          placeholder="username"
          value={newUser.username}
          onChange={(e) => setNewUser({ ...newUser, username: e.target.value })}
        />
        <input
          aria-label="new user password"
          className="rounded bg-zinc-800 px-2 py-1"
          placeholder="password (min 8)"
          type="password"
          value={newUser.password}
          onChange={(e) => setNewUser({ ...newUser, password: e.target.value })}
        />
        <select
          aria-label="new user role"
          className="rounded bg-zinc-800 px-1 py-1"
          value={newUser.role}
          onChange={(e) => setNewUser({ ...newUser, role: e.target.value })}
        >
          {ROLES.map((r) => (
            <option key={r}>{r}</option>
          ))}
        </select>
        <button
          className="rounded bg-emerald-700 px-3 py-1 font-medium hover:bg-emerald-600 disabled:opacity-40"
          disabled={busy}
          onClick={createUser}
        >
          Create user
        </button>
      </div>
    </div>
  );
}
