"use client";

import { useState } from "react";
import { useAuth } from "@/lib/auth";
import LoginPanel from "@/components/LoginPanel";
import ChatTab from "@/components/ChatTab";
import VoiceTab from "@/components/VoiceTab";
import VisionTab from "@/components/VisionTab";
import MemoryTab from "@/components/MemoryTab";
import ToolsTab from "@/components/ToolsTab";
import SettingsTab from "@/components/SettingsTab";
import PolicyConsole from "@/components/PolicyConsole";
import ApprovalQueue from "@/components/ApprovalQueue";
import AuditExplorer from "@/components/AuditExplorer";
import HealthDashboard from "@/components/HealthDashboard";
import PlannerTab from "@/components/PlannerTab";
import AgentsTab from "@/components/AgentsTab";
import KnowledgeTab from "@/components/KnowledgeTab";
import MemoryGovTab from "@/components/MemoryGovTab";
import CostTab from "@/components/CostTab";
import ComplianceTab from "@/components/ComplianceTab";
import EvalsTab from "@/components/EvalsTab";

type TabDef = { name: string; minRole: string; render: () => JSX.Element };

const TABS: TabDef[] = [
  { name: "Chat", minRole: "readonly", render: () => <ChatTab /> },
  { name: "Voice", minRole: "user", render: () => <VoiceTab /> },
  { name: "Vision", minRole: "user", render: () => <VisionTab /> },
  { name: "Memory", minRole: "user", render: () => <MemoryTab /> },
  { name: "Tools/Logs", minRole: "user", render: () => <ToolsTab /> },
  { name: "Planner", minRole: "user", render: () => <PlannerTab /> },
  { name: "Agents", minRole: "user", render: () => <AgentsTab /> },
  { name: "Knowledge", minRole: "user", render: () => <KnowledgeTab /> },
  { name: "Memory Gov", minRole: "user", render: () => <MemoryGovTab /> },
  { name: "Cost", minRole: "operator", render: () => <CostTab /> },
  { name: "Policy", minRole: "operator", render: () => <PolicyConsole /> },
  { name: "Approvals", minRole: "operator", render: () => <ApprovalQueue /> },
  { name: "Audit", minRole: "operator", render: () => <AuditExplorer /> },
  { name: "Compliance", minRole: "operator", render: () => <ComplianceTab /> },
  { name: "Evals", minRole: "operator", render: () => <EvalsTab /> },
  { name: "Health", minRole: "operator", render: () => <HealthDashboard /> },
  { name: "Settings", minRole: "readonly", render: () => <SettingsTab /> },
];

export default function Home() {
  const { user, hasRole, logoutUser } = useAuth();
  const [tab, setTab] = useState("Chat");

  if (!user) return <LoginPanel />;

  const visible = TABS.filter((t) => hasRole(t.minRole));
  const active = visible.find((t) => t.name === tab) ?? visible[0];

  return (
    <main className="mx-auto flex min-h-screen max-w-5xl flex-col p-4">
      <header className="mb-4 flex items-center justify-between">
        <h1 className="text-xl font-semibold tracking-tight">
          JARVIS <span className="text-emerald-400">God Mode Agent</span>
        </h1>
        <div className="flex items-center gap-3 text-xs text-zinc-400">
          <span>
            {user.username} ·{" "}
            <span className="text-emerald-400">{user.role}</span> @{" "}
            {user.tenant}
          </span>
          <button
            onClick={logoutUser}
            className="rounded-md bg-zinc-800 px-3 py-1 hover:bg-zinc-700"
          >
            Sign out
          </button>
        </div>
      </header>

      <nav
        className="mb-4 flex flex-wrap gap-1 rounded-lg bg-zinc-900 p-1"
        role="tablist"
      >
        {visible.map((t) => (
          <button
            key={t.name}
            role="tab"
            aria-selected={active?.name === t.name}
            onClick={() => setTab(t.name)}
            className={`rounded-md px-3 py-1.5 text-sm transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400 ${
              active?.name === t.name
                ? "bg-emerald-600 text-white"
                : "text-zinc-400 hover:text-zinc-100"
            }`}
          >
            {t.name}
          </button>
        ))}
      </nav>

      <section className="flex-1">{active?.render()}</section>
    </main>
  );
}
