"use client";

import { useEffect, useState } from "react";
import { BACKEND, getJson } from "@/lib/api";

type HealthData = {
  status: string;
  version: string;
  providers: Record<string, boolean>;
  default_provider: string;
  default_model: string;
  fallbacks_enabled: boolean;
  memory_backend: string;
  tools: string[];
};

export default function SettingsTab() {
  const [health, setHealth] = useState<HealthData | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    getJson<HealthData>("/health").then((r) => {
      if (r.success) setHealth(r.data);
      else setError(`${r.error.code}: ${r.error.message}`);
    });
  }, []);

  return (
    <div className="space-y-4 text-sm">
      <div className="rounded-lg border border-zinc-800 p-4">
        <h2 className="mb-2 font-medium text-emerald-400">Backend</h2>
        <p>
          URL: <code className="text-zinc-300">{BACKEND}</code>
        </p>
        <p className="mt-1 text-xs text-zinc-500">
          Override with <code>NEXT_PUBLIC_BACKEND_URL</code> (local dev reads BACKEND_PUBLIC_URL from{" "}
          <code>.env</code>; on Vercel set it in project environment variables).
        </p>
        {error && <p className="mt-2 text-amber-400">⚠ {error} - is the API running?</p>}
      </div>

      {health && (
        <>
          <div className="rounded-lg border border-zinc-800 p-4">
            <h2 className="mb-2 font-medium text-emerald-400">Providers (from server env)</h2>
            <ul className="space-y-1">
              {Object.entries(health.providers).map(([name, configured]) => (
                <li key={name}>
                  <span className={configured ? "text-emerald-400" : "text-zinc-500"}>
                    {configured ? "●" : "○"}
                  </span>{" "}
                  {name} {configured ? "configured" : "not configured (fallback/mock in use)"}
                </li>
              ))}
            </ul>
            <p className="mt-2 text-xs text-zinc-500">
              Default: {health.default_provider} / {health.default_model} · fallbacks{" "}
              {health.fallbacks_enabled ? "enabled" : "disabled"} · memory backend:{" "}
              {health.memory_backend}
            </p>
          </div>
          <div className="rounded-lg border border-zinc-800 p-4">
            <h2 className="mb-2 font-medium text-emerald-400">Enabled tools</h2>
            <p className="text-zinc-300">{health.tools.join(", ")}</p>
          </div>
        </>
      )}
    </div>
  );
}
