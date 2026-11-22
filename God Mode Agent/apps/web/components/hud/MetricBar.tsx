"use client";

// Clean-room labeled meter: 0-100 value, amber >70, red >90.

export default function MetricBar({ label, value, unit = "%" }: { label: string; value: number; unit?: string }) {
  const pct = Math.max(0, Math.min(100, value));
  const color = pct > 90 ? "bg-red-500" : pct > 70 ? "bg-amber-500" : "bg-cyan-400";
  return (
    <div className="text-[11px]">
      <div className="flex justify-between text-zinc-400">
        <span>{label}</span>
        <span className="font-mono text-zinc-300">
          {value.toFixed(0)}
          {unit}
        </span>
      </div>
      <div className="mt-0.5 h-1.5 w-full overflow-hidden rounded bg-zinc-800">
        <div className={`h-full ${color}`} style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}
