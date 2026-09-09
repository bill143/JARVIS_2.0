"use client";

// Run 2: per-agent Kokoro voice picker (full catalog + live preview) and
// model tier selection (voice tier + console tier), all persisted server-side.

import { useEffect, useRef, useState } from "react";
import { delJson, getBlob, getJson, putJson } from "@/lib/api";
import { useAuth } from "@/lib/auth";

type VoicesData = {
  voices: string[];
  agents: Record<string, string>;
  default_agent: string;
  overrides: Record<string, string>;
};

type ModelData = {
  voice: string;
  console: string;
  defaults: { provider: string; voice_model: string; console_model: string };
  catalog: string[];
};

export default function VoiceModelPanel() {
  const { hasRole } = useAuth();
  const canEdit = hasRole("admin");
  const [voicesData, setVoicesData] = useState<VoicesData | null>(null);
  const [modelData, setModelData] = useState<ModelData | null>(null);
  const [selected, setSelected] = useState<Record<string, string>>({});
  const [voiceTier, setVoiceTier] = useState("");
  const [consoleTier, setConsoleTier] = useState("");
  const [status, setStatus] = useState("");
  const [busy, setBusy] = useState("");
  const audioRef = useRef<HTMLAudioElement | null>(null);

  async function load() {
    const [v, m] = await Promise.all([
      getJson<VoicesData>("/voices"),
      getJson<ModelData>("/settings/model"),
    ]);
    if (v.success) {
      setVoicesData(v.data);
      setSelected(v.data.agents);
    } else {
      setStatus(`${v.error.code}: ${v.error.message}`);
    }
    if (m.success) {
      setModelData(m.data);
      setVoiceTier(m.data.voice);
      setConsoleTier(m.data.console);
    }
  }
  useEffect(() => {
    load();
  }, []);

  async function preview(voice: string) {
    setBusy(`preview:${voice}`);
    const blob = await getBlob(`/voices/preview?voice=${encodeURIComponent(voice)}`);
    setBusy("");
    if (!blob) {
      setStatus(`preview failed for ${voice} — is Kokoro running?`);
      return;
    }
    audioRef.current?.pause();
    const url = URL.createObjectURL(blob);
    const audio = new Audio(url);
    audioRef.current = audio;
    audio.onended = () => URL.revokeObjectURL(url);
    audio.play().catch(() => setStatus("playback blocked — click the page first"));
  }

  async function saveVoice(agent: string) {
    setBusy(`save:${agent}`);
    const r = await putJson<VoicesData>(`/agents/${agent}/voice`, { voice: selected[agent] });
    setBusy("");
    setStatus(r.success ? `${agent} voice saved (persisted)` : `${r.error.code}: ${r.error.message}`);
    if (r.success) load();
  }

  async function clearVoice(agent: string) {
    setBusy(`clear:${agent}`);
    const r = await delJson<VoicesData>(`/agents/${agent}/voice`);
    setBusy("");
    setStatus(r.success ? `${agent} override cleared — voices.yaml value applies` : `${r.error.code}: ${r.error.message}`);
    if (r.success) load();
  }

  async function saveModels() {
    setBusy("models");
    const r = await putJson<ModelData>("/settings/model", { voice: voiceTier, console: consoleTier });
    setBusy("");
    setStatus(r.success ? "model tiers saved — applies to the next request" : `${r.error.code}: ${r.error.message}`);
    if (r.success) load();
  }

  return (
    <div className="rounded-lg border border-zinc-800 p-4">
      <h2 className="mb-2 font-medium text-emerald-400">Voices &amp; models</h2>
      {status && <p className="mb-2 text-xs text-amber-400">{status}</p>}

      <h3 className="mb-1 text-xs uppercase tracking-wide text-zinc-500">
        Agent voices {voicesData ? `(${voicesData.voices.length} Kokoro voices)` : ""}
      </h3>
      {!voicesData ? (
        <p className="text-xs text-zinc-500">loading voice catalog…</p>
      ) : voicesData.voices.length === 0 ? (
        <p className="text-xs text-red-400">Kokoro service unreachable — no catalog</p>
      ) : (
        <div className="space-y-1">
          {Object.entries(voicesData.agents).map(([agent, voice]) => (
            <div key={agent} className="flex flex-wrap items-center gap-2 text-xs">
              <span className="w-24 font-mono text-zinc-300">{agent}</span>
              <select
                aria-label={`voice for ${agent}`}
                value={selected[agent] ?? voice}
                disabled={!canEdit}
                onChange={(e) => setSelected((s) => ({ ...s, [agent]: e.target.value }))}
                className="rounded bg-zinc-800 px-2 py-1"
              >
                {voicesData.voices.map((v) => (
                  <option key={v} value={v}>{v}</option>
                ))}
              </select>
              <button
                onClick={() => preview(selected[agent] ?? voice)}
                disabled={busy !== ""}
                className="rounded bg-zinc-700 px-2 py-1 hover:bg-zinc-600 disabled:opacity-40"
                aria-label={`preview ${selected[agent] ?? voice}`}
              >
                {busy === `preview:${selected[agent] ?? voice}` ? "…" : "▶ preview"}
              </button>
              {canEdit && (
                <button
                  onClick={() => saveVoice(agent)}
                  disabled={busy !== "" || (selected[agent] ?? voice) === voice}
                  className="rounded bg-emerald-700 px-2 py-1 font-medium hover:bg-emerald-600 disabled:opacity-40"
                >
                  Save
                </button>
              )}
              {canEdit && voicesData.overrides[agent] && (
                <button
                  onClick={() => clearVoice(agent)}
                  disabled={busy !== ""}
                  className="rounded bg-zinc-700 px-2 py-1 hover:bg-zinc-600 disabled:opacity-40"
                  title="remove the persisted override; voices.yaml applies again"
                >
                  Clear override
                </button>
              )}
              {voicesData.overrides[agent] && (
                <span className="text-[10px] text-sky-400">override</span>
              )}
            </div>
          ))}
        </div>
      )}

      <h3 className="mb-1 mt-4 text-xs uppercase tracking-wide text-zinc-500">Model tiers</h3>
      {!modelData ? (
        <p className="text-xs text-zinc-500">loading…</p>
      ) : (
        <div className="space-y-1 text-xs">
          <datalist id="model-catalog">
            {modelData.catalog.map((m) => (
              <option key={m} value={m} />
            ))}
          </datalist>
          <div className="flex flex-wrap items-center gap-2">
            <span className="w-24 font-mono text-zinc-300">voice tier</span>
            <input
              aria-label="voice tier model"
              list="model-catalog"
              value={voiceTier}
              disabled={!canEdit}
              onChange={(e) => setVoiceTier(e.target.value)}
              placeholder={`default: ${modelData.defaults.voice_model}`}
              className="w-80 rounded bg-zinc-800 px-2 py-1"
            />
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <span className="w-24 font-mono text-zinc-300">console tier</span>
            <input
              aria-label="console tier model"
              list="model-catalog"
              value={consoleTier}
              disabled={!canEdit}
              onChange={(e) => setConsoleTier(e.target.value)}
              placeholder={`default: ${modelData.defaults.console_model}`}
              className="w-80 rounded bg-zinc-800 px-2 py-1"
            />
          </div>
          {canEdit && (
            <button
              onClick={saveModels}
              disabled={busy !== ""}
              className="mt-1 rounded bg-emerald-700 px-3 py-1 font-medium hover:bg-emerald-600 disabled:opacity-40"
            >
              {busy === "models" ? "Saving…" : "Save model tiers"}
            </button>
          )}
          <p className="text-[10px] text-zinc-500">
            {modelData.catalog.length > 0
              ? `${modelData.catalog.length} models live from ${modelData.defaults.provider}`
              : "model catalog unavailable — type a model id"}
            {" · "}empty field = provider default · changes hot-swap, no restart
          </p>
        </div>
      )}
    </div>
  );
}
