"use client";

import { useState } from "react";
import { postJson } from "@/lib/api";
import { useAuth } from "@/lib/auth";

export default function AccountPanel() {
  const { user, logoutUser } = useAuth();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState("");
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

  async function changePassword() {
    setError("");
    if (next.length < 8) {
      setError("new password must be at least 8 characters");
      return;
    }
    if (next !== confirm) {
      setError("new password and confirmation do not match");
      return;
    }
    setBusy(true);
    const r = await postJson("/auth/change-password", {
      current_password: current,
      new_password: next,
    });
    setBusy(false);
    if (r.success) {
      // Server revoked every session for this account — show the notice
      // briefly, then drop ours so the login panel takes over.
      setDone(true);
      setTimeout(logoutUser, 2500);
    } else {
      setError(`${r.error.code}: ${r.error.message}`);
    }
  }

  if (done) {
    return (
      <div className="rounded-lg border border-emerald-800 bg-emerald-950/30 p-4 text-sm text-emerald-300">
        Password changed. All sessions were signed out — redirecting to sign-in…
      </div>
    );
  }

  return (
    <div className="rounded-lg border border-zinc-800 p-4">
      <h2 className="mb-2 font-medium text-emerald-400">
        Account — {user?.username} ({user?.role})
      </h2>
      {error && <p className="mb-2 text-xs text-red-400">⚠ {error}</p>}
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <input
          aria-label="current password"
          className="rounded bg-zinc-800 px-2 py-1"
          placeholder="current password"
          type="password"
          value={current}
          onChange={(e) => setCurrent(e.target.value)}
        />
        <input
          aria-label="new password"
          className="rounded bg-zinc-800 px-2 py-1"
          placeholder="new password (min 8)"
          type="password"
          value={next}
          onChange={(e) => setNext(e.target.value)}
        />
        <input
          aria-label="confirm new password"
          className="rounded bg-zinc-800 px-2 py-1"
          placeholder="confirm new password"
          type="password"
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
        />
        <button
          className="rounded bg-emerald-700 px-3 py-1 font-medium hover:bg-emerald-600 disabled:opacity-40"
          disabled={busy || !current || !next}
          onClick={changePassword}
        >
          Change password
        </button>
      </div>
      <p className="mt-2 text-xs text-zinc-500">
        Changing your password signs out every active session for this account.
      </p>
    </div>
  );
}
