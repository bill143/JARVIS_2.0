"use client";

import { useState } from "react";
import { useAuth } from "@/lib/auth";

export default function LoginPanel() {
  const { loginUser } = useAuth();
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    const err = await loginUser(username, password);
    if (err) setError(err);
    setBusy(false);
  }

  return (
    <div className="mx-auto mt-24 max-w-sm rounded-lg border border-zinc-800 p-6">
      <h2 className="mb-1 text-lg font-semibold">Sign in to JARVIS</h2>
      <p className="mb-4 text-xs text-zinc-500">
        Sign in with your JARVIS account
      </p>
      <form onSubmit={submit} className="space-y-3">
        <input
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          placeholder="Username"
          aria-label="Username"
          className="w-full rounded-md bg-zinc-900 px-3 py-2 text-sm outline-none ring-emerald-500 focus:ring-2"
        />
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder="Password"
          aria-label="Password"
          className="w-full rounded-md bg-zinc-900 px-3 py-2 text-sm outline-none ring-emerald-500 focus:ring-2"
        />
        <button
          type="submit"
          disabled={busy}
          className="w-full rounded-md bg-emerald-600 px-4 py-2 text-sm font-medium disabled:opacity-50"
        >
          {busy ? "Signing in…" : "Sign in"}
        </button>
        {error && <p className="text-sm text-red-400">{error}</p>}
      </form>
    </div>
  );
}
