"use client";

// Clean-room system readout for the left rail. Wired to the REAL existing
// endpoints (/observability/slo, /observability/summary) — no fabricated host
// metrics. Host CPU/RAM/GPU bars can be added once a /system/metrics endpoint
// exists; until then this shows genuine runtime signals.

import { useEffect, useState } from "react";
import { getJson } from "@/lib/api";
import MetricBar from "./MetricBar";

type Slo = { latency: { avg_ms: number }; tool_success_rate: number };
type Summary = {
  queue: { depth: number; dead: number; backend: string };
  circuit_breakers: { name: string; state: string }[];
  audit: { ok: boolean; entries?: number };
};

function Row({ k, v, warn }: { k: string; v: string; warn?: boolean }) {
  return (
    <div className="flex justify-between">
      <span className="text-zinc-500">{k}</span>
      <span className={warn ? "font-mono text-amber-400" : "font-mono text-zinc-300"}>{v}</span>
    </div>
  );
}

export default function SystemRail() {
  const [slo, setSlo] = useState<Slo | null>(null);
  const [sum, setSum] = useState<Summary | null>(null);

  useEffect(() => {
    let live = true;
    const load = async () => {
      const [a, b] = await Promise.all([
        getJson<Slo>("/observability/slo"),
        getJson<Summary>("/observability/summary"),
      ]);
      if (!live) return;
      if (a.success) setSlo(a.data);
      if (b.success) setSum(b.data);
    };
    load();
    const id = setInterval(load, 5000);
    return () => {
      live = false;
      clearInterval(id);
    };
  }, []);

  const breakersOpen = sum?.circuit_breakers.filter((b) => b.state !== "closed").length ?? 0;

  return (
    <div className="rounded-lg border border-zinc-800 p-2 text-[11px]">
      <p className="mb-1 text-[10px] uppercase tracking-wide text-zinc-500">System</p>
      <div className="space-y-2">
        <MetricBar label="Tool success" value={(slo?.tool_success_rate ?? 1) * 100} />
        <Row k="Latency" v={slo ? `${slo.latency.avg_ms.toFixed(0)}ms` : "—"} />
        <Row k="Queue" v={sum ? `${sum.queue.depth} (${sum.queue.backend})` : "—"} />
        <Row k="Dead-letters" v={sum ? String(sum.queue.dead) : "—"} warn={(sum?.queue.dead ?? 0) > 0} />
        <Row k="Breakers" v={sum ? (breakersOpen ? `${breakersOpen} open` : "all closed") : "—"} warn={breakersOpen > 0} />
        <Row
          k="Audit"
          v={sum ? (sum.audit.ok ? `✓ ${sum.audit.entries ?? 0}` : "✗ tampered") : "—"}
          warn={sum ? !sum.audit.ok : false}
        />
      </div>
    </div>
  );
}
