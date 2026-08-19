"use client";

import { useState } from "react";
import { useAuth } from "@/lib/auth";
import LoginPanel from "@/components/LoginPanel";
import StatusStrip from "@/components/StatusStrip";
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

type Group = "assistant" | "governance";
type PanelDef = { name: string; group: Group; minRole: string; render: () => JSX.Element };

const PANELS: PanelDef[] = [
  { name: "Voice", group: "assistant", minRole: "user", render: () => <VoiceTab /> },
  { name: "Vision", group: "assistant", minRole: "user", render: () => <VisionTab /> },
  { name: "Memory", group: "assistant", minRole: "user", render: () => <MemoryTab /> },
  { name: "Tools/Logs", group: "assistant", minRole: "user", render: () => <ToolsTab /> },
  { name: "Planner", group: "assistant", minRole: "user", render: () => <PlannerTab /> },
  { name: "Agents", group: "assistant", minRole: "user", render: () => <AgentsTab /> },
  { name: "Knowledge", group: "assistant", minRole: "user", render: () => <KnowledgeTab /> },
  { name: "Memory Gov", group: "assistant", minRole: "user", render: () => <MemoryGovTab /> },
  { name: "Cost", group: "assistant", minRole: "operator", render: () => <CostTab /> },
  { name: "Policy", group: "governance", minRole: "operator", render: () => <PolicyConsole /> },
  { name: "Approvals", group: "governance", minRole: "operator", render: () => <ApprovalQueue /> },
  { name: "Audit", group: "governance", minRole: "operator", render: () => <AuditExplorer /> },
  { name: "Compliance", group: "governance", minRole: "operator", render: () => <ComplianceTab /> },
  { name: "Evals", group: "governance", minRole: "operator", render: () => <EvalsTab /> },
  { name: "Health", group: "governance", minRole: "operator", render: () => <HealthDashboard /> },
  { name: "Settings", group: "governance", minRole: "readonly", render: () => <SettingsTab /> },
];

export default function Home() {
  const { user, hasRole, logoutUser } = useAuth();
  const [panel, setPanel] = useState<PanelDef | null>(null);

  if (!user) return <LoginPanel />;

  const railButton = (p: PanelDef) => {
    const allowed = hasRole(p.minRole);
    const activeName = panel?.name === p.name;
    return (
      <button
        key={p.name}
        disabled={!allowed}
        title={allowed ? p.name : `requires ${p.minRole} role`}
        aria-current={activeName ? "true" : undefined}
        onClick={() => setPanel(activeName ? null : p)}
        className={`w-full rounded-md px-2 py-1 text-left text-xs transition-colors ${
          activeName
            ? "bg-emerald-600 text-white"
            : allowed
              ? "text-zinc-300 hover:bg-zinc-800"
              : "cursor-not-allowed text-zinc-600"
        }`}
      >
        {p.name}
        {!allowed && <span className="ml-1 text-[9px] text-zinc-600">●</span>}
      </button>
    );
  };

  const assistant = PANELS.filter((p) => p.group === "assistant");
  const governance = PANELS.filter((p) => p.group === "governance");

  return (
    <main className="flex h-screen flex-col">
      <header className="flex flex-wrap items-center justify-between gap-2 border-b border-zinc-800 px-4 py-2">
        <h1 className="text-lg font-semibold tracking-tight">
          JARVIS <span className="text-emerald-400">Control Center</span>
        </h1>
        <StatusStrip />
        <div className="flex items-center gap-3 text-xs text-zinc-400">
          <span>
            {user.username} · <span className="text-emerald-400">{user.role}</span> @ {user.tenant}
          </span>
          <button onClick={logoutUser} className="rounded-md bg-zinc-800 px-3 py-1 hover:bg-zinc-700">
            Sign out
          </button>
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden">
        {/* Rail */}
        <aside className="w-44 shrink-0 overflow-y-auto border-r border-zinc-800 p-2">
          <button
            onClick={() => setPanel(null)}
            aria-current={panel === null ? "true" : undefined}
            className={`mb-2 w-full rounded-md px-2 py-1.5 text-left text-sm font-medium ${
              panel === null ? "bg-emerald-600 text-white" : "text-zinc-200 hover:bg-zinc-800"
            }`}
          >
            ◉ JARVIS · Chat
          </button>
          <p className="mt-2 px-2 text-[10px] uppercase tracking-wide text-zinc-500">Assistant</p>
          <div className="space-y-0.5">{assistant.map(railButton)}</div>
          <p className="mt-3 px-2 text-[10px] uppercase tracking-wide text-zinc-500">Governance</p>
          <div className="space-y-0.5">{governance.map(railButton)}</div>
        </aside>

        {/* Primary surface: Chat is always present */}
        <section className="flex-1 overflow-y-auto p-4">
          <ChatTab />
        </section>

        {/* Contextual side panel next to chat */}
        {panel && (
          <aside className="flex w-[440px] shrink-0 flex-col overflow-hidden border-l border-zinc-800">
            <div className="flex items-center justify-between border-b border-zinc-800 px-3 py-2">
              <h2 className="text-sm font-medium text-emerald-400">{panel.name}</h2>
              <button onClick={() => setPanel(null)} className="text-xs text-zinc-400 hover:text-zinc-100" aria-label="close panel">
                ✕ close
              </button>
            </div>
            <div className="flex-1 overflow-y-auto p-3">{panel.render()}</div>
          </aside>
        )}
      </div>
    </main>
  );
}
