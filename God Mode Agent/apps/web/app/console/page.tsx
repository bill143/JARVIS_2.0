"use client";

// ECHO Command console route (moved from `/` in Stage 5 — the voice presence
// now owns `/`). Content unchanged from the Stage 3 dashboard, plus optional
// `?panel=<name>` deep-linking used by the voice route's command palette.

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { useAuth } from "@/lib/auth";
import LoginPanel from "@/components/LoginPanel";
import StatusStrip from "@/components/StatusStrip";
import HudCanvas from "@/components/hud/HudCanvas";
import SystemRail from "@/components/hud/SystemRail";
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
import IntegrationsPanel from "@/components/IntegrationsPanel";
import SystemHealthRow from "@/components/ops/SystemHealthRow";
import AgentCards from "@/components/ops/AgentCards";
import ActivityFeed from "@/components/ops/ActivityFeed";
import { useAgentActivity } from "@/lib/activity";

type Group = "assistant" | "governance";
type PanelDef = { name: string; group: Group; minRole: string; render: () => JSX.Element };

const HUD_ENABLED = process.env.NEXT_PUBLIC_HUD_ENABLED !== "false";

const PANELS: PanelDef[] = [
  { name: "Voice", group: "assistant", minRole: "user", render: () => <VoiceTab /> },
  { name: "Vision", group: "assistant", minRole: "user", render: () => <VisionTab /> },
  { name: "Memory", group: "assistant", minRole: "user", render: () => <MemoryTab /> },
  { name: "Tools/Logs", group: "assistant", minRole: "user", render: () => <ToolsTab /> },
  { name: "Planner", group: "assistant", minRole: "user", render: () => <PlannerTab /> },
  { name: "Agents", group: "assistant", minRole: "user", render: () => <AgentsTab /> },
  { name: "Knowledge", group: "assistant", minRole: "user", render: () => <KnowledgeTab /> },
  { name: "Integrations", group: "assistant", minRole: "user", render: () => <IntegrationsPanel /> },
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

function panelByName(wanted: string | null): PanelDef | null {
  if (!wanted) return null;
  return PANELS.find((p) => p.name.toLowerCase() === wanted.toLowerCase()) ?? null;
}

export default function ConsolePage() {
  // useSearchParams needs a Suspense boundary during prerender.
  return (
    <Suspense fallback={null}>
      <ConsoleView />
    </Suspense>
  );
}

function ConsoleView() {
  const { user, hasRole, logoutUser } = useAuth();
  const searchParams = useSearchParams();
  const [panel, setPanel] = useState<PanelDef | null>(null);
  const agents = useAgentActivity();

  // Deep links (?panel=) arrive via client-side push from the palette/overlay,
  // so this must react to the query changing, not just to first mount. The role
  // check mirrors the rail button — a deep link must not open a panel the user
  // is not allowed to see (the API enforces it too, this keeps the UI honest).
  const wantedPanel = searchParams.get("panel");
  useEffect(() => {
    const target = panelByName(wantedPanel);
    if (target && hasRole(target.minRole)) setPanel(target);
    else if (wantedPanel === null) setPanel(null);
  }, [wantedPanel, hasRole]);
  // HUD state derives from the activity log: any agent active -> processing.
  const hudState = agents?.some((a) => a.status === "active") ? "processing" : "idle";

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
        className={`w-auto rounded-md px-2 py-1 text-left text-xs transition-colors md:w-full ${
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
    <main className="flex h-[100dvh] flex-col">
      <header className="flex flex-wrap items-center justify-between gap-2 border-b border-zinc-800 px-4 py-2">
        <h1 className="text-lg font-semibold tracking-tight">
          ECHO <span className="text-emerald-400">Command</span>
        </h1>
        <StatusStrip />
        <div className="flex items-center gap-3 text-xs text-zinc-400">
          <span>
            {user.username} · <span className="text-emerald-400">{user.role}</span> @ {user.tenant}
          </span>
          <Link href="/" className="rounded-md bg-zinc-800 px-3 py-1 hover:bg-zinc-700">
            ⦿ Voice
          </Link>
          <button onClick={logoutUser} className="rounded-md bg-zinc-800 px-3 py-1 hover:bg-zinc-700">
            Sign out
          </button>
        </div>
      </header>

      {/* Below md the rail and panel stack above/below the main column instead
          of forcing a ~1060px minimum width; from md up the 3-column desktop
          layout is unchanged. */}
      <div className="flex flex-1 flex-col overflow-y-auto md:flex-row md:overflow-hidden">
        <aside className="w-full shrink-0 border-b border-zinc-800 p-2 md:w-44 md:overflow-y-auto md:border-b-0 md:border-r">
          <SystemRail />
          <button
            onClick={() => setPanel(null)}
            aria-current={panel === null ? "true" : undefined}
            className={`mb-2 mt-2 w-full rounded-md px-2 py-1.5 text-left text-sm font-medium ${
              panel === null ? "bg-emerald-600 text-white" : "text-zinc-200 hover:bg-zinc-800"
            }`}
          >
            ◉ ECHO · Chat
          </button>
          <p className="mt-2 px-2 text-[10px] uppercase tracking-wide text-zinc-500">Assistant</p>
          <div className="flex flex-wrap gap-1 md:block md:space-y-0.5">{assistant.map(railButton)}</div>
          <p className="mt-3 px-2 text-[10px] uppercase tracking-wide text-zinc-500">Governance</p>
          <div className="flex flex-wrap gap-1 md:block md:space-y-0.5">{governance.map(railButton)}</div>
        </aside>

        <section className="flex-1 space-y-3 p-4 md:overflow-y-auto">
          {HUD_ENABLED && <HudCanvas state={hudState} compact={panel !== null} />}
          <SystemHealthRow />
          <AgentCards agents={agents} />
          <ChatTab />
          <ActivityFeed />
        </section>

        {panel && (
          <aside className="flex w-full shrink-0 flex-col border-t border-zinc-800 md:w-[440px] md:overflow-hidden md:border-l md:border-t-0">
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
