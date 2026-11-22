"use client";

import { useEffect, useState } from "react";
import { getJson } from "@/lib/api";

type Summary = {
  metrics: {
    counters: Record<string, number>;
    gauges: Record<string, number>;
    histograms: Record<string, { count: number; avg: number }>;
  };
  circuit_breakers: { name: string; state: string; failures: number }[];
  queue: {
    backend: string;
    queued: number;
    running: number;
    done: number;
    dead: number;
    depth: number;
  };
  audit: { ok: boolean; entries?: number };
};

export default function HealthDashboard() {
  const [summary, setSummary] = useState<Summary | null>(null);
  const [error, setError] = useState("");

  async function load() {
    const resp = await getJson<Summary>("/observability/summary");
    if (resp.success) setSummary(resp.data);
    else setError(`${resp.error.code}: ${resp.error.message}`);
  }

  useEffect(() => {
    load();
    const t = setInterval(load, 4000);
    return () => clearInterval(t);
  }, []);

  if (error) return <p className="text-amber-400">⚠ {error}</p>;
  if (!summary) return <p className="text-zinc-500">Loading metrics…</p>;

  return (
    <div className="grid gap-4 md:grid-cols-2">
      <Card title="Queue">
        <Kv k="backend" v={summary.queue.backend} />
        <Kv k="depth" v={summary.queue.depth} />
        <Kv k="done" v={summary.queue.done} />
        <Kv k="dead-letters" v={summary.queue.dead} />
      </Card>
      <Card title="Circuit breakers">
        {summary.circuit_breakers.length === 0 && (
          <p className="text-zinc-500">none tripped</p>
        )}
        {summary.circuit_breakers.map((b) => (
          <Kv key={b.name} k={b.name} v={`${b.state} (${b.failures} fails)`} />
        ))}
      </Card>
      <Card title="Audit chain">
        <Kv k="integrity" v={summary.audit.ok ? "✓ intact" : "✗ tampered"} />
        <Kv k="entries" v={summary.audit.entries ?? 0} />
      </Card>
      <Card title="Counters">
        {Object.entries(summary.metrics.counters)
          .slice(0, 12)
          .map(([k, v]) => (
            <Kv key={k} k={k} v={v} />
          ))}
      </Card>
    </div>
  );
}

function Card({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div className="rounded-lg border border-zinc-800 p-4">
      <h2 className="mb-2 text-sm font-medium text-emerald-400">{title}</h2>
      <div className="space-y-1 text-sm">{children}</div>
    </div>
  );
}

function Kv({ k, v }: { k: string; v: string | number }) {
  return (
    <div className="flex justify-between gap-2">
      <span className="truncate text-zinc-500" title={k}>
        {k}
      </span>
      <span className="shrink-0 font-mono">{v}</span>
    </div>
  );
}
