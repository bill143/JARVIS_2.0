"use client";

// ECHO voice presence — JARVIS_UI_LOCKED_SPEC.md implementation.
// The orb is the interface; text is the fallback path. Four states only.
// Speaking-state amplitude comes from the returned Kokoro WAV via the
// WebAudio graph (never the mic); listening amplitude comes from the mic.

import { useCallback, useEffect, useRef, useState } from "react";
import { blobToBase64, getJson, wsUrl } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import LoginPanel from "@/components/LoginPanel";
import Orb from "@/components/voice/Orb";
import {
  CommandPalette, ErrorStrip, SystemsOverlay, TextInputBar, TranscriptOverlay,
} from "@/components/voice/overlays";
import type { OrbState, Turn } from "@/lib/voiceTypes";
import type { ActivityRow } from "@/lib/activity";
import { timeAgo } from "@/lib/activity";

const PANEL_NAMES = [
  "Voice", "Vision", "Memory", "Tools/Logs", "Planner", "Agents", "Knowledge",
  "Integrations", "Memory Gov", "Cost", "Policy", "Approvals", "Audit",
  "Compliance", "Evals", "Health", "Settings",
];

const STATE_LABEL: Record<OrbState, [string, string]> = {
  standby: ["STANDBY", "hold space to speak"],
  listening: ["LISTENING", "release to send"],
  thinking: ["PROCESSING", "reasoning"],
  speaking: ["SPEAKING", "esc to interrupt"],
};

const mono = { fontFamily: "var(--font-mono-jb), monospace" } as const;
const corner = {
  ...mono, position: "fixed" as const, zIndex: 10, fontSize: 11, opacity: 0.3,
  color: "var(--ink)", lineHeight: "16px", textTransform: "uppercase" as const,
  letterSpacing: ".13em", pointerEvents: "none" as const, maxWidth: "34vw",
  whiteSpace: "nowrap" as const, overflow: "hidden",
};

type Services = Record<string, { status: string; detail: string }>;
type VoiceEvent = { type: string; text?: string; message?: string; audio_b64?: string; media_type?: string; engine?: string };

export default function VoiceRoute() {
  const { user } = useAuth();
  const [orbState, setOrbState] = useState<OrbState>("standby");
  const [caption, setCaption] = useState("");
  const [partial, setPartial] = useState("");
  const [turns, setTurns] = useState<Turn[]>([]);
  const [errNonce, setErrNonce] = useState(0);
  const [textOpen, setTextOpen] = useState(false);
  const [transcriptOpen, setTranscriptOpen] = useState(false);
  const [systemsOpen, setSystemsOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [wsReady, setWsReady] = useState(false);
  const [services, setServices] = useState<Services | null>(null);
  const [recent, setRecent] = useState<ActivityRow[]>([]);
  const [version, setVersion] = useState("");
  const [providerLine, setProviderLine] = useState("");

  const wsRef = useRef<WebSocket | null>(null);
  const audioCtxRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const micStreamRef = useRef<MediaStream | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const ttsSourceRef = useRef<AudioBufferSourceNode | null>(null);
  const stateRef = useRef<OrbState>("standby");
  stateRef.current = orbState;

  const bumpErr = useCallback(() => setErrNonce((n) => n + 1), []);

  const stopTts = useCallback((barge: boolean) => {
    try { ttsSourceRef.current?.stop(); } catch { /* already stopped */ }
    ttsSourceRef.current = null;
    if (barge && wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify({ type: "barge_in" }));
    }
    setOrbState("standby");
  }, []);

  const playTts = useCallback(async (b64: string) => {
    try {
      const ctx = audioCtxRef.current ?? new AudioContext();
      audioCtxRef.current = ctx;
      if (ctx.state === "suspended") await ctx.resume();
      const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
      const buffer = await ctx.decodeAudioData(bytes.buffer.slice(0));
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 512;
      analyser.smoothingTimeConstant = 0.72;
      const source = ctx.createBufferSource();
      source.buffer = buffer;
      source.connect(analyser);
      analyser.connect(ctx.destination);
      source.onended = () => {
        if (ttsSourceRef.current === source) {
          ttsSourceRef.current = null;
          setOrbState("standby");
        }
      };
      analyserRef.current = analyser; // speaking amplitude = returned WAV
      ttsSourceRef.current = source;
      setOrbState("speaking");
      source.start();
    } catch {
      bumpErr();
      setOrbState("standby");
    }
  }, [bumpErr]);

  // WebSocket lifecycle with reconnect.
  useEffect(() => {
    if (!user) return;
    let closed = false;
    let retry: ReturnType<typeof setTimeout> | null = null;
    const connect = () => {
      const ws = new WebSocket(wsUrl("/realtime/voice"));
      wsRef.current = ws;
      ws.onopen = () => setWsReady(true);
      ws.onclose = () => {
        setWsReady(false);
        if (!closed) retry = setTimeout(connect, 1500);
      };
      ws.onerror = () => bumpErr();
      ws.onmessage = (msg) => {
        const ev: VoiceEvent = JSON.parse(msg.data);
        if (ev.type === "transcript.partial") setPartial(ev.text ?? "");
        else if (ev.type === "transcript.final") {
          setPartial("");
          if (ev.text) setTurns((t) => [...t.slice(-60), { role: "you", text: ev.text ?? "", at: Date.now() }]);
        } else if (ev.type === "reply.text") {
          setCaption(ev.text ?? "");
          if (ev.text) setTurns((t) => [...t.slice(-60), { role: "echo", text: ev.text ?? "", at: Date.now() }]);
        } else if (ev.type === "tts.audio") {
          if (ev.audio_b64) void playTts(ev.audio_b64);
          else setOrbState("standby");
        } else if (ev.type === "interrupted") {
          stopTts(false);
        } else if (ev.type === "error") {
          bumpErr();
          if (stateRef.current === "thinking") setOrbState("standby");
        }
      };
    };
    connect();
    return () => {
      closed = true;
      if (retry) clearTimeout(retry);
      wsRef.current?.close();
    };
  }, [user, bumpErr, playTts, stopTts]);

  // Live telemetry (real endpoints; corners render nothing until data exists).
  useEffect(() => {
    if (!user) return;
    let live = true;
    const health = async () => {
      const r = await getJson<{ services: Services }>("/system/health");
      if (live && r.success) setServices(r.data.services);
    };
    const activity = async () => {
      const r = await getJson<{ rows: ActivityRow[] }>("/activity/recent?limit=3");
      if (live && r.success) setRecent(r.data.rows);
    };
    const info = async () => {
      const r = await getJson<{ version: string; default_provider: string; default_model: string }>("/health");
      if (live && r.success) {
        setVersion(r.data.version);
        setProviderLine(`${r.data.default_provider} · ${r.data.default_model}`);
      }
    };
    health(); activity(); info();
    const a = setInterval(health, 30000);
    const b = setInterval(activity, 15000);
    return () => { live = false; clearInterval(a); clearInterval(b); };
  }, [user]);

  const startListening = useCallback(async () => {
    if (stateRef.current === "listening") return;
    if (stateRef.current === "speaking") stopTts(true);
    if (wsRef.current?.readyState !== WebSocket.OPEN) {
      // Don't record into a void — surface the dead link before capturing.
      bumpErr();
      setCaption("link down — reconnecting, try again in a moment");
      return;
    }
    try {
      const ctx = audioCtxRef.current ?? new AudioContext();
      audioCtxRef.current = ctx;
      if (ctx.state === "suspended") await ctx.resume();
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      micStreamRef.current = stream;
      const srcNode = ctx.createMediaStreamSource(stream);
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 512;
      analyser.smoothingTimeConstant = 0.72;
      srcNode.connect(analyser); // analysis only — mic never reaches speakers
      analyserRef.current = analyser;
      const recorder = new MediaRecorder(stream);
      recorder.ondataavailable = async (e) => {
        if (e.data.size > 0 && wsRef.current?.readyState === WebSocket.OPEN) {
          wsRef.current.send(JSON.stringify({ type: "audio", data_b64: await blobToBase64(e.data) }));
        }
      };
      recorder.onstop = () => {
        stream.getTracks().forEach((t) => t.stop());
        micStreamRef.current = null;
        if (wsRef.current?.readyState === WebSocket.OPEN) {
          wsRef.current.send(JSON.stringify({ type: "commit" }));
          setOrbState("thinking");
        } else {
          // Link down: the utterance is lost — say so instead of failing silently.
          bumpErr();
          setCaption("link down — reconnecting, try again in a moment");
          setOrbState("standby");
        }
      };
      recorder.start(250);
      recorderRef.current = recorder;
      setOrbState("listening");
    } catch {
      bumpErr();
    }
  }, [bumpErr, stopTts]);

  const stopListening = useCallback(() => {
    if (stateRef.current !== "listening") return;
    recorderRef.current?.stop();
    recorderRef.current = null;
  }, []);

  const sendText = useCallback((text: string) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify({ type: "text", text }));
      setOrbState("thinking");
    } else {
      bumpErr();
    }
  }, [bumpErr]);

  // §5 input model.
  useEffect(() => {
    if (!user) return;
    const isTyping = (t: EventTarget | null) =>
      t instanceof HTMLElement && (t.tagName === "INPUT" || t.tagName === "TEXTAREA");
    const down = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        if (stateRef.current === "speaking") stopTts(true);
        setTextOpen(false); setTranscriptOpen(false); setSystemsOpen(false); setPaletteOpen(false);
        return;
      }
      if (isTyping(e.target)) return;
      if (e.key === "k" && (e.ctrlKey || e.metaKey)) {
        e.preventDefault(); setPaletteOpen((v) => !v);
      } else if (e.code === "Space" && !e.repeat) {
        e.preventDefault(); void startListening();
      } else if (e.key === "/") {
        e.preventDefault(); setTextOpen(true);
      } else if (e.key === "t" || e.key === "T") {
        setTranscriptOpen((v) => !v);
      } else if (e.key === "s" || e.key === "S") {
        setSystemsOpen((v) => !v);
      }
    };
    const up = (e: KeyboardEvent) => {
      if (e.code === "Space" && !isTyping(e.target)) stopListening();
    };
    window.addEventListener("keydown", down);
    window.addEventListener("keyup", up);
    return () => {
      window.removeEventListener("keydown", down);
      window.removeEventListener("keyup", up);
    };
  }, [user, startListening, stopListening, stopTts]);

  if (!user) return <LoginPanel />;

  const [label, subLabel] = STATE_LABEL[orbState];
  const dot = (s: string) => (s === "ok" ? "var(--cyan)" : s === "degraded" ? "var(--amber)" : "var(--red)");
  const railBtn = {
    ...mono, background: "none", border: "none", cursor: "pointer",
    color: "var(--ink-2)", fontSize: 10, textTransform: "uppercase" as const,
    letterSpacing: ".13em", padding: "0 14px",
  };

  return (
    <div className="voice-root" style={{ background: "var(--bg)", color: "var(--ink)", height: "100dvh", display: "flex", flexDirection: "column", overflow: "hidden" }}>
      <ErrorStrip nonce={errNonce} />

      {/* peripheral telemetry — ≤4 lines/corner, ≤11px, ≤30% opacity, no cards */}
      <div style={{ ...corner, top: 14, left: 18 }}>
        <div style={{ fontFamily: "var(--font-syne), sans-serif", fontWeight: 800, fontSize: 14, letterSpacing: ".28em", color: "var(--cyan)", opacity: 1 }}>ECHO</div>
        {version && <div>v{version}</div>}
        {services?.tailscale && <div>{services.tailscale.detail.replace("https://", "").split(" ")[0]}</div>}
      </div>
      <div style={{ ...corner, top: 14, right: 18, textAlign: "right" }}>
        {services &&
          Object.entries(services).map(([name, s]) => (
            <div key={name}>
              <span style={{ color: dot(s.status) }}>●</span> {name} {s.status}
            </div>
          ))}
      </div>
      <div style={{ ...corner, bottom: 58, left: 18 }}>
        {recent.slice(0, 3).map((r) => (
          <div key={r.id}>{timeAgo(r.timestamp)} {r.agent} · {r.task.slice(0, 34)}</div>
        ))}
      </div>
      <div style={{ ...corner, bottom: 58, right: 18, textAlign: "right" }}>
        {providerLine && <div>{providerLine}</div>}
        <div>link {wsReady ? "open" : "reconnecting"}</div>
      </div>

      {/* §1: main is a 3-row grid and nothing else */}
      <main style={{ flex: 1, minHeight: 0, display: "grid", gridTemplateRows: "1fr auto auto" }}>
        <div style={{ minHeight: 0, padding: "24px 0 0" }}>
          <Orb
            state={orbState}
            analyserRef={analyserRef}
            onPressStart={() => void startListening()}
            onPressEnd={stopListening}
          />
        </div>
        <div style={{ textAlign: "center", paddingTop: 10 }}>
          <div style={{ ...mono, fontSize: 13, letterSpacing: ".28em", color: orbState === "thinking" ? "var(--amber)" : "var(--cyan)" }}>{label}</div>
          <div style={{ ...mono, fontSize: 10, letterSpacing: ".2em", color: "var(--ink-3)", marginTop: 4, textTransform: "uppercase" }}>{subLabel}</div>
        </div>
        <div style={{ minHeight: 132, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "flex-start", padding: "16px 8vw 8px", textAlign: "center" }}>
          {partial && (
            <p style={{ fontFamily: "var(--font-outfit), sans-serif", fontWeight: 300, fontSize: 15, color: "var(--ink-2)", fontStyle: "italic" }}>{partial}…</p>
          )}
          {caption && (
            <p style={{ fontFamily: "var(--font-outfit), sans-serif", fontWeight: 300, fontSize: 19, lineHeight: "28px", color: "var(--ink)", maxWidth: 780, display: "-webkit-box", WebkitLineClamp: 3, WebkitBoxOrient: "vertical", overflow: "hidden" }}>{caption}</p>
          )}
        </div>
      </main>

      {/* bottom rail — ≤44px, hairline top border, text buttons only */}
      <footer style={{ height: 44, borderTop: "1px solid var(--hair)", display: "flex", alignItems: "center", justifyContent: "center", flexShrink: 0 }}>
        <button style={railBtn} onPointerDown={() => void startListening()} onPointerUp={stopListening} aria-label="Hold to talk">talk · hold</button>
        <button style={railBtn} onClick={() => setTextOpen(true)}>text /</button>
        <button style={railBtn} onClick={() => setTranscriptOpen(true)}>transcript t</button>
        <button style={railBtn} onClick={() => setSystemsOpen(true)}>systems s</button>
        <button style={railBtn} onClick={() => setPaletteOpen(true)}>palette ctrl+k</button>
        <a style={{ ...railBtn, textDecoration: "none", lineHeight: "44px" }} href="/console">console →</a>
      </footer>

      <TextInputBar open={textOpen} onSend={sendText} onClose={() => setTextOpen(false)} />
      {transcriptOpen && <TranscriptOverlay turns={turns} onClose={() => setTranscriptOpen(false)} />}
      {systemsOpen && <SystemsOverlay panels={PANEL_NAMES} onClose={() => setSystemsOpen(false)} />}
      {paletteOpen && <CommandPalette panels={PANEL_NAMES} onClose={() => setPaletteOpen(false)} />}
    </div>
  );
}
