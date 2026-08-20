"use client";

// Recent activity feed: last 20 activity-log rows, newest first, polled live.

import { useEffect, useState } from "react";
import { getJson } from "@/lib/api";
import type { ActivityRow } from "@/lib/activity";
import { timeAgo } from "@/lib/activity";

const STATUS_COLOR: Record<ActivityRow["status"], string> = {
  completed: "text-emerald-400",
  failed: "text-red-400",
  started: "text-sky-400",
};

export default function ActivityFeed() {
  const [rows, setRows] = useState<ActivityRow[] | null>(null);

  useEffect(() => {
    let live = true;
    const load = async () => {
      const r = await getJson<{ rows: ActivityRow[] }>("/activity/recent?limit=20");
      if (live && r.success) setRows(r.data.rows);
    };
    load();
    const id = setInterval(load, 15000);
    return () => {
      live = false;
      clearInterval(id);
    };
  }, []);

  return (
    <div className="rounded-lg border border-zinc-800 p-3">
      <p className="mb-2 text-[10px] uppercase tracking-wide text-zinc-500">Recent activity</p>
      {!rows ? (
        <p className="text-[11px] text-zinc-500">loading…</p>
      ) : rows.length === 0 ? (
        <p className="text-[11px] text-zinc-500">no data yet</p>
      ) : (
        <ul className="space-y-1">
          {rows.map((r) => (
            <li key={r.id} className="flex items-baseline gap-2 text-[11px]">
              <span className="w-14 shrink-0 font-mono text-zinc-600">{timeAgo(r.timestamp)}</span>
              <span className="w-24 shrink-0 truncate text-zinc-400">{r.agent}</span>
              <span className={`w-16 shrink-0 ${STATUS_COLOR[r.status]}`}>{r.status}</span>
              <span className="truncate text-zinc-300" title={r.detail || r.task}>{r.task}</span>
              {r.model && <span className="shrink-0 font-mono text-[10px] text-zinc-600">{r.model}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
