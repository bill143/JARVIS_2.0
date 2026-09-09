"use client";

import { useEffect, useState } from "react";
import { getJson } from "@/lib/api";

type Health = {
  version: string;
  providers: Record<string, boolean>;
  default_provider: string;
  default_model: string;
  memory_backend: string;
  queue_backend: string;
  auth_mode: string;
  policy_default: string;
};

export default function StatusStrip() {
  const [h, setH] = useState<Health | null>(null);
  const [err, setErr] = useState(false);

  useEffect(() => {
    let live = true;
    const load = async () => {
      const r = await getJson<Health>("/health");
      if (!live) return;
      if (r.success) {
        setH(r.data);
        setErr(false);
      } else {
        setErr(true);
      }
    };
    load();
    const id = setInterval(load, 30000);
    return () => {
      live = false;
      clearInterval(id);
    };
  }, []);

  if (err) return <span className="text-[11px] text-red-400">● backend unreachable</span>;
  if (!h) return <span className="text-[11px] text-zinc-500">● connecting…</span>;

  const commercial = ["openai", "anthropic"].some((p) => h.providers[p]);
  const health = commercial
    ? { label: "Healthy", cls: "text-emerald-400" }
    : { label: "Degraded (mock fallback)", cls: "text-amber-400" };

  const Item = ({ k, v }: { k: string; v: string }) => (
    <span className="whitespace-nowrap">
      <span className="text-zinc-500">{k}</span> <span className="text-zinc-300">{v}</span>
    </span>
  );

  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px]" aria-label="system status">
      <span className={health.cls} title={commercial ? "commercial provider configured" : "no commercial keys — mock fallback active, chat still works"}>
        ● {health.label}
      </span>
      <Item k="provider" v={`${h.default_provider}/${h.default_model}`} />
      <Item k="memory" v={h.memory_backend} />
      <Item k="queue" v={h.queue_backend} />
      <Item k="policy" v={h.policy_default} />
      <Item k="v" v={h.version} />
    </div>
  );
}
