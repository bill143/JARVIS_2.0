"use client";

// Real health checks polled every 30s: backend API, Kokoro TTS, Tailscale
// serve. Colors derive from actual responses — never hardcoded.

import { useEffect, useState } from "react";
import { getJson } from "@/lib/api";

type ServiceHealth = { status: "ok" | "degraded" | "down"; detail: string };
type Services = { backend: ServiceHealth; kokoro: ServiceHealth; tailscale: ServiceHealth };

const LABELS: { key: keyof Services; label: string }[] = [
  { key: "backend", label: "Backend" },
  { key: "kokoro", label: "Kokoro TTS" },
  { key: "tailscale", label: "Tailscale" },
];

const DOT: Record<ServiceHealth["status"], string> = {
  ok: "text-emerald-400",
  degraded: "text-amber-400",
  down: "text-red-400",
};

export default function SystemHealthRow() {
  const [services, setServices] = useState<Services | null>(null);
  const [unreachable, setUnreachable] = useState(false);

  useEffect(() => {
    let live = true;
    const load = async () => {
      const r = await getJson<{ services: Services }>("/system/health");
      if (!live) return;
      if (r.success) {
        setServices(r.data.services);
        setUnreachable(false);
      } else {
        setUnreachable(true);
      }
    };
    load();
    const id = setInterval(load, 30000);
    return () => {
      live = false;
      clearInterval(id);
    };
  }, []);

  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded-lg border border-zinc-800 px-3 py-2 text-[11px]"
         aria-label="service health">
      <span className="text-[10px] uppercase tracking-wide text-zinc-500">Services</span>
      {unreachable ? (
        <span className="text-red-400">● backend unreachable — health checks unavailable</span>
      ) : !services ? (
        <span className="text-zinc-500">checking…</span>
      ) : (
        LABELS.map(({ key, label }) => (
          <span key={key} className="whitespace-nowrap" title={services[key].detail}>
            <span className={DOT[services[key].status]}>●</span>{" "}
            <span className="text-zinc-300">{label}</span>{" "}
            <span className="text-zinc-500">{services[key].status}</span>
          </span>
        ))
      )}
    </div>
  );
}
