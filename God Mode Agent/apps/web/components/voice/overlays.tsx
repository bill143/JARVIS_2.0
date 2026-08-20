"use client";

// Overlays for the ECHO voice route: error strip (§3), slide-up text input,
// transcript overlay, systems overlay, command palette (§5, §7).
// Hairlines and plain text only — no cards, no fills, no message bubbles.

import { useEffect, useRef, useState } from "react";
import { getJson } from "@/lib/api";
import type { ActivityRow } from "@/lib/activity";
import { timeAgo } from "@/lib/activity";
import type { Turn } from "@/lib/voiceTypes";

const mono = { fontFamily: "var(--font-mono-jb), monospace" } as const;

// §3: errors are a 9px amber strip sliding down for 4.2s — no error screens.
export function ErrorStrip({ nonce }: { nonce: number }) {
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    if (nonce === 0) return;
    setVisible(true);
    const t = setTimeout(() => setVisible(false), 4200);
    return () => clearTimeout(t);
  }, [nonce]);
  return (
    <div
      aria-hidden={!visible}
      style={{
        position: "fixed", top: 0, left: 0, right: 0, height: 9,
        background: "var(--amber)", zIndex: 60,
        transform: visible ? "translateY(0)" : "translateY(-100%)",
        transition: "transform 240ms ease",
      }}
    />
  );
}

export function TextInputBar({
  open, onSend, onClose,
}: { open: boolean; onSend: (text: string) => void; onClose: () => void }) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [value, setValue] = useState("");
  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);
  return (
    <div
      style={{
        position: "fixed", left: 0, right: 0, bottom: 44, zIndex: 40,
        transform: open ? "translateY(0)" : "translateY(120%)",
        transition: "transform 200ms ease",
        borderTop: "1px solid var(--hair)", background: "var(--bg)",
      }}
    >
      <input
        ref={inputRef}
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={(e) => {
          e.stopPropagation();
          if (e.key === "Enter" && value.trim()) {
            onSend(value.trim());
            setValue("");
            onClose();
          } else if (e.key === "Escape") {
            setValue("");
            onClose();
          }
        }}
        aria-label="Type to ECHO"
        placeholder="type — enter to send, esc to close"
        style={{
          ...mono, width: "100%", background: "transparent", border: "none",
          outline: "none", color: "var(--ink)", fontSize: 13,
          padding: "10px 16px", letterSpacing: ".05em",
        }}
      />
    </div>
  );
}

function OverlayShell({ title, onClose, children }: {
  title: string; onClose: () => void; children: React.ReactNode;
}) {
  return (
    <div
      role="dialog"
      aria-label={title}
      style={{
        position: "fixed", inset: 0, zIndex: 50, background: "var(--bg)",
        display: "flex", flexDirection: "column", padding: "48px 6vw",
      }}
    >
      <div style={{ display: "flex", justifyContent: "space-between", borderBottom: "1px solid var(--hair)", paddingBottom: 12 }}>
        <span style={{ ...mono, color: "var(--ink-2)", fontSize: 11, textTransform: "uppercase", letterSpacing: ".28em" }}>
          {title}
        </span>
        <button onClick={onClose} style={{ ...mono, background: "none", border: "none", color: "var(--ink-3)", fontSize: 11, cursor: "pointer", textTransform: "uppercase", letterSpacing: ".13em" }}>
          esc to close
        </button>
      </div>
      <div style={{ flex: 1, overflowY: "auto", paddingTop: 16 }}>{children}</div>
    </div>
  );
}

export function TranscriptOverlay({ turns, onClose }: { turns: Turn[]; onClose: () => void }) {
  return (
    <OverlayShell title="Transcript" onClose={onClose}>
      {turns.length === 0 ? (
        <p style={{ ...mono, color: "var(--ink-3)", fontSize: 12 }}>no turns yet</p>
      ) : (
        turns.map((t, i) => (
          <div key={i} style={{ padding: "10px 0", borderBottom: "1px solid var(--hair-2)" }}>
            <span style={{ ...mono, color: t.role === "you" ? "var(--cyan-hot)" : "var(--cyan)", fontSize: 10, textTransform: "uppercase", letterSpacing: ".2em" }}>
              {t.role === "you" ? "you" : "echo"}
            </span>
            <p style={{ color: "var(--ink)", fontSize: 14, marginTop: 4, fontFamily: "var(--font-outfit), sans-serif", fontWeight: 300 }}>
              {t.text}
            </p>
          </div>
        ))
      )}
    </OverlayShell>
  );
}

type ServiceHealth = { status: string; detail: string };
type Services = Record<string, ServiceHealth>;

export function SystemsOverlay({ onClose, panels }: { onClose: () => void; panels: string[] }) {
  const [services, setServices] = useState<Services | null>(null);
  const [rows, setRows] = useState<ActivityRow[] | null>(null);
  useEffect(() => {
    let live = true;
    const load = async () => {
      const [h, a] = await Promise.all([
        getJson<{ services: Services }>("/system/health"),
        getJson<{ rows: ActivityRow[] }>("/activity/recent?limit=10"),
      ]);
      if (!live) return;
      if (h.success) setServices(h.data.services);
      if (a.success) setRows(a.data.rows);
    };
    load();
    const id = setInterval(load, 15000);
    return () => { live = false; clearInterval(id); };
  }, []);

  const dot = (s: string) => (s === "ok" ? "var(--cyan)" : s === "degraded" ? "var(--amber)" : "var(--red)");

  return (
    <OverlayShell title="Systems" onClose={onClose}>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(260px, 1fr))", gap: 32 }}>
        <section>
          <h3 style={{ ...mono, color: "var(--ink-3)", fontSize: 10, textTransform: "uppercase", letterSpacing: ".28em", marginBottom: 10 }}>services</h3>
          {!services ? (
            <p style={{ ...mono, color: "var(--ink-3)", fontSize: 12 }}>checking…</p>
          ) : (
            Object.entries(services).map(([name, s]) => (
              <p key={name} style={{ ...mono, fontSize: 12, color: "var(--ink-2)", margin: "6px 0" }}>
                <span style={{ color: dot(s.status) }}>●</span>{" "}
                <span style={{ textTransform: "uppercase", letterSpacing: ".13em" }}>{name}</span>{" "}
                {s.status} <span style={{ color: "var(--ink-3)" }}>{s.detail}</span>
              </p>
            ))
          )}
        </section>
        <section>
          <h3 style={{ ...mono, color: "var(--ink-3)", fontSize: 10, textTransform: "uppercase", letterSpacing: ".28em", marginBottom: 10 }}>recent activity</h3>
          {!rows ? (
            <p style={{ ...mono, color: "var(--ink-3)", fontSize: 12 }}>loading…</p>
          ) : rows.length === 0 ? (
            <p style={{ ...mono, color: "var(--ink-3)", fontSize: 12 }}>no data yet</p>
          ) : (
            rows.map((r) => (
              <p key={r.id} style={{ ...mono, fontSize: 11, color: "var(--ink-2)", margin: "5px 0", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                <span style={{ color: "var(--ink-3)" }}>{timeAgo(r.timestamp)}</span>{" "}
                <span style={{ color: r.status === "failed" ? "var(--red)" : "var(--cyan)" }}>{r.agent}</span>{" "}
                {r.task}
              </p>
            ))
          )}
        </section>
        <section>
          <h3 style={{ ...mono, color: "var(--ink-3)", fontSize: 10, textTransform: "uppercase", letterSpacing: ".28em", marginBottom: 10 }}>console panels</h3>
          {panels.map((p) => (
            <a key={p} href={`/console?panel=${encodeURIComponent(p)}`}
               style={{ ...mono, display: "block", fontSize: 12, color: "var(--ink-2)", margin: "6px 0", textDecoration: "none", letterSpacing: ".08em" }}>
              → {p}
            </a>
          ))}
        </section>
      </div>
    </OverlayShell>
  );
}

export function CommandPalette({ onClose, panels }: { onClose: () => void; panels: string[] }) {
  const [query, setQuery] = useState("");
  const [index, setIndex] = useState(0);
  const inputRef = useRef<HTMLInputElement | null>(null);
  useEffect(() => inputRef.current?.focus(), []);

  const entries = ["Console", ...panels].filter((p) => p.toLowerCase().includes(query.toLowerCase()));
  const go = (entry: string) => {
    window.location.href = entry === "Console" ? "/console" : `/console?panel=${encodeURIComponent(entry)}`;
  };

  return (
    <div role="dialog" aria-label="Command palette"
         style={{ position: "fixed", inset: 0, zIndex: 55, background: "rgba(0,3,8,.88)", display: "flex", justifyContent: "center", paddingTop: "18vh" }}
         onClick={onClose}>
      <div style={{ width: "min(560px, 90vw)", height: "fit-content", borderTop: "1px solid var(--hair)", borderBottom: "1px solid var(--hair)", background: "var(--bg)", padding: "8px 0" }}
           onClick={(e) => e.stopPropagation()}>
        <input
          ref={inputRef}
          value={query}
          onChange={(e) => { setQuery(e.target.value); setIndex(0); }}
          onKeyDown={(e) => {
            e.stopPropagation();
            if (e.key === "Escape") onClose();
            else if (e.key === "ArrowDown") setIndex((i) => Math.min(i + 1, entries.length - 1));
            else if (e.key === "ArrowUp") setIndex((i) => Math.max(i - 1, 0));
            else if (e.key === "Enter" && entries[index]) go(entries[index]);
          }}
          aria-label="Search destinations"
          placeholder="where to?"
          style={{ ...mono, width: "100%", background: "transparent", border: "none", outline: "none", color: "var(--ink)", fontSize: 13, padding: "8px 16px", letterSpacing: ".05em" }}
        />
        <div style={{ maxHeight: "40vh", overflowY: "auto" }}>
          {entries.map((p, i) => (
            <button key={p} onClick={() => go(p)}
              style={{ ...mono, display: "block", width: "100%", textAlign: "left", background: "transparent", border: "none", cursor: "pointer",
                       color: i === index ? "var(--cyan-hot)" : "var(--ink-2)", fontSize: 12, padding: "6px 16px", letterSpacing: ".1em" }}>
              {i === index ? "› " : "  "}{p}
            </button>
          ))}
          {entries.length === 0 && <p style={{ ...mono, color: "var(--ink-3)", fontSize: 12, padding: "6px 16px" }}>no match</p>}
        </div>
      </div>
    </div>
  );
}
