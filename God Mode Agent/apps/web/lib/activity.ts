"use client";

// ECHO Command Stage 3: live activity-log reads. All values come from the
// backend activity log — no fabricated numbers, no placeholder statuses.

import { useEffect, useState } from "react";
import { getJson } from "@/lib/api";

export type AgentSummary = {
  agent: string;
  status: "active" | "idle" | "no_data";
  last_timestamp: string | null;
  last_task: string | null;
  last_status: string | null;
  today: { completed: number; failed: number; started: number; total: number };
};

export type ActivityRow = {
  id: number;
  timestamp: string;
  agent: string;
  task: string;
  status: "started" | "completed" | "failed";
  model: string;
  detail: string;
};

export function useAgentActivity(intervalMs = 15000): AgentSummary[] | null {
  const [agents, setAgents] = useState<AgentSummary[] | null>(null);
  useEffect(() => {
    let live = true;
    const load = async () => {
      const r = await getJson<{ agents: AgentSummary[] }>("/activity/agents");
      if (live && r.success) setAgents(r.data.agents);
    };
    load();
    const id = setInterval(load, intervalMs);
    return () => {
      live = false;
      clearInterval(id);
    };
  }, [intervalMs]);
  return agents;
}

export function timeAgo(iso: string | null): string {
  if (!iso) return "no data yet";
  const seconds = Math.max(0, (Date.now() - Date.parse(iso)) / 1000);
  if (seconds < 60) return `${Math.floor(seconds)}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return `${Math.floor(seconds / 86400)}d ago`;
}
