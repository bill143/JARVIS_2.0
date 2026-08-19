"use client";

import { useRef, useState } from "react";
import { postJson, wsUrl, type ToolEvent } from "@/lib/api";

type ChatItem =
  | { kind: "user"; text: string }
  | { kind: "assistant"; text: string; provider?: string }
  | { kind: "tool"; event: ToolEvent }
  | { kind: "error"; text: string };

type ChatData = {
  reply: string;
  provider: string;
  model: string;
  iterations: number;
  tool_events: ToolEvent[];
  session_id: string;
};

export default function ChatTab() {
  const [items, setItems] = useState<ChatItem[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const sessionRef = useRef(`web-${Math.random().toString(36).slice(2, 8)}`);

  const push = (item: ChatItem) => setItems((prev) => [...prev, item]);

  async function sendViaRest(message: string) {
    const resp = await postJson<ChatData>("/chat", {
      message,
      session_id: sessionRef.current,
    });
    if (resp.success) {
      resp.data.tool_events.forEach((event) => push({ kind: "tool", event }));
      push({ kind: "assistant", text: resp.data.reply, provider: resp.data.provider });
    } else {
      push({ kind: "error", text: `${resp.error.code}: ${resp.error.message}` });
    }
  }

  function send() {
    const message = input.trim();
    if (!message || busy) return;
    setInput("");
    setBusy(true);
    push({ kind: "user", text: message });

    // Stream over websocket; fall back to REST if the socket fails.
    let streamed = "";
    let settled = false;
    const settle = () => {
      if (!settled) {
        settled = true;
        setBusy(false);
      }
    };
    try {
      const ws = new WebSocket(wsUrl("/realtime/chat"));
      ws.onopen = () => ws.send(JSON.stringify({ message, session_id: sessionRef.current }));
      ws.onmessage = (msg) => {
        const event = JSON.parse(msg.data);
        if (event.type === "tool_event") {
          push({ kind: "tool", event });
        } else if (event.type === "token") {
          streamed += event.text;
          setItems((prev) => {
            const next = [...prev];
            const last = next[next.length - 1];
            if (last?.kind === "assistant") next[next.length - 1] = { kind: "assistant", text: streamed };
            else next.push({ kind: "assistant", text: streamed });
            return next;
          });
        } else if (event.type === "done") {
          setItems((prev) => {
            const next = [...prev];
            const last = next[next.length - 1];
            const final = {
              kind: "assistant" as const,
              text: event.data.reply,
              provider: event.data.provider,
            };
            if (last?.kind === "assistant") next[next.length - 1] = final;
            else next.push(final);
            return next;
          });
          ws.close();
          settle();
        } else if (event.type === "error") {
          push({ kind: "error", text: event.message });
          ws.close();
          settle();
        }
      };
      ws.onerror = () => {
        ws.close();
        sendViaRest(message).finally(settle);
      };
    } catch {
      sendViaRest(message).finally(settle);
    }
  }

  return (
    <div className="flex h-[70vh] flex-col rounded-lg border border-zinc-800">
      <div className="flex-1 space-y-3 overflow-y-auto p-4">
        {items.length === 0 && (
          <p className="text-sm text-zinc-500">
            Try: <code className="text-emerald-400">compute 2+2</code>,{" "}
            <code className="text-emerald-400">search for multimodal agents</code>, or{" "}
            <code className="text-emerald-400">take a webcam snapshot</code>
          </p>
        )}
        {items.map((item, i) => {
          if (item.kind === "tool")
            return (
              <div key={i} className="rounded border border-amber-900/50 bg-amber-950/30 p-2 text-xs">
                <span className={item.event.status === "ok" ? "text-emerald-400" : "text-red-400"}>
                  ⚙ {item.event.tool}
                </span>{" "}
                <span className="text-zinc-400">
                  ({item.event.duration_ms.toFixed(0)}ms) {item.event.summary.slice(0, 160)}
                </span>
              </div>
            );
          if (item.kind === "error")
            return (
              <div key={i} className="rounded bg-red-950/50 p-2 text-sm text-red-300">
                {item.text}
              </div>
            );
          return (
            <div
              key={i}
              className={`max-w-[85%] whitespace-pre-wrap rounded-lg p-3 text-sm ${
                item.kind === "user" ? "ml-auto bg-emerald-900/40" : "bg-zinc-900"
              }`}
            >
              {item.text}
              {item.kind === "assistant" && item.provider && (
                <div className="mt-1 text-[10px] uppercase tracking-wide text-zinc-500">
                  via {item.provider}
                </div>
              )}
            </div>
          );
        })}
        {busy && <p className="animate-pulse text-xs text-zinc-500">thinking…</p>}
      </div>
      <div className="flex gap-2 border-t border-zinc-800 p-3">
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && send()}
          placeholder="Message JARVIS…"
          aria-label="Chat message"
          className="flex-1 rounded-md bg-zinc-900 px-3 py-2 text-sm outline-none ring-emerald-500 focus:ring-2"
        />
        <button
          onClick={send}
          disabled={busy}
          className="rounded-md bg-emerald-600 px-4 py-2 text-sm font-medium disabled:opacity-50"
        >
          Send
        </button>
      </div>
    </div>
  );
}
