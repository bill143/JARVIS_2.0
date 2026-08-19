"use client";

import { useRef, useState } from "react";
import { postJson, wsUrl, type ToolEvent } from "@/lib/api";

type Item =
  | { id: string; kind: "user"; text: string }
  | { id: string; kind: "assistant"; text: string; provider?: string; model?: string }
  | { id: string; kind: "tool"; event: ToolEvent }
  | { id: string; kind: "error"; text: string };

type ChatData = {
  reply: string;
  provider: string;
  model: string;
  iterations: number;
  tool_events: ToolEvent[];
  session_id: string;
};

let _seq = 0;
const nid = () => `m${++_seq}`;

export default function ChatTab() {
  const [items, setItems] = useState<Item[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const sessionRef = useRef(`web-${Math.random().toString(36).slice(2, 8)}`);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);

  const push = (item: Item) => setItems((prev) => [...prev, item]);

  async function runTurn(message: string) {
    setBusy(true);
    push({ id: nid(), kind: "user", text: message });
    const assistantId = nid();
    let streamed = "";
    let gotAssistant = false;

    const upsertAssistant = (text: string, provider?: string, model?: string) => {
      gotAssistant = true;
      setItems((prev) => {
        const next = [...prev];
        const idx = next.findIndex((x) => x.id === assistantId);
        const val: Item = { id: assistantId, kind: "assistant", text, provider, model };
        if (idx >= 0) next[idx] = val;
        else next.push(val);
        return next;
      });
    };

    const viaRest = async () => {
      const resp = await postJson<ChatData>("/chat", { message, session_id: sessionRef.current });
      if (resp.success) {
        resp.data.tool_events.forEach((event) => push({ id: nid(), kind: "tool", event }));
        upsertAssistant(resp.data.reply, resp.data.provider, resp.data.model);
      } else {
        push({ id: nid(), kind: "error", text: `${resp.error.code}: ${resp.error.message}` });
      }
    };

    await new Promise<void>((resolve) => {
      let settled = false;
      const done = () => {
        if (!settled) {
          settled = true;
          setBusy(false);
          resolve();
        }
      };
      let ws: WebSocket;
      try {
        ws = new WebSocket(wsUrl("/realtime/chat"));
      } catch {
        viaRest().finally(done);
        return;
      }
      const failToRest = () => {
        try {
          ws.close();
        } catch {
          /* ignore */
        }
        if (!gotAssistant) viaRest().finally(done);
        else done();
      };
      ws.onopen = () => ws.send(JSON.stringify({ message, session_id: sessionRef.current }));
      ws.onerror = failToRest;
      ws.onmessage = (msg) => {
        let event: { type: string; text?: string; message?: string; code?: string; data?: ChatData } & Record<string, unknown>;
        try {
          event = JSON.parse(msg.data);
        } catch {
          return;
        }
        if (event.type === "tool_event") {
          setItems((prev) => {
            const next = [...prev];
            const idx = next.findIndex((x) => x.id === assistantId);
            const toolItem: Item = { id: nid(), kind: "tool", event: event as unknown as ToolEvent };
            if (idx >= 0) next.splice(idx, 0, toolItem);
            else next.push(toolItem);
            return next;
          });
        } else if (event.type === "token") {
          streamed += event.text ?? "";
          upsertAssistant(streamed);
        } else if (event.type === "done" && event.data) {
          upsertAssistant(event.data.reply, event.data.provider, event.data.model);
          try {
            ws.close();
          } catch {
            /* ignore */
          }
          done();
        } else if (event.type === "error") {
          push({ id: nid(), kind: "error", text: event.code ? `${event.code}: ${event.message}` : String(event.message) });
          try {
            ws.close();
          } catch {
            /* ignore */
          }
          done();
        }
      };
    });
  }

  function send() {
    const message = input.trim();
    if (!message || busy) return;
    setInput("");
    // Preserve the composer text if the turn throws unexpectedly.
    runTurn(message).catch(() => setInput(message));
  }

  function retryLast() {
    if (busy) return;
    const lastUserIdx = items.map((x) => x.kind).lastIndexOf("user");
    if (lastUserIdx < 0) return;
    const msg = (items[lastUserIdx] as { text: string }).text;
    setItems((prev) => prev.slice(0, lastUserIdx));
    runTurn(msg);
  }

  function editResend(idx: number) {
    if (busy) return;
    const it = items[idx];
    if (it.kind !== "user") return;
    setInput(it.text);
    setItems((prev) => prev.slice(0, idx));
    inputRef.current?.focus();
  }

  const hasUser = items.some((x) => x.kind === "user");

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
              <div key={item.id} className="rounded border border-amber-900/50 bg-amber-950/30 p-2 text-xs">
                <span className={item.event.status === "ok" ? "text-emerald-400" : "text-red-400"}>⚙ {item.event.tool}</span>{" "}
                <span className="text-zinc-400">({item.event.duration_ms.toFixed(0)}ms) {item.event.summary.slice(0, 200)}</span>
              </div>
            );
          if (item.kind === "error")
            return (
              <div key={item.id} className="flex items-center justify-between gap-2 rounded bg-red-950/50 p-2 text-sm text-red-300">
                <span>{item.text}</span>
                <button onClick={retryLast} disabled={busy} className="shrink-0 rounded bg-red-800/60 px-2 py-0.5 text-xs hover:bg-red-700 disabled:opacity-50">Retry</button>
              </div>
            );
          return (
            <div key={item.id} className={`group max-w-[85%] rounded-lg p-3 text-sm ${item.kind === "user" ? "ml-auto bg-emerald-900/40" : "bg-zinc-900"}`}>
              <div className="whitespace-pre-wrap">{item.text}</div>
              {item.kind === "user" && (
                <button onClick={() => editResend(i)} disabled={busy} className="mt-1 text-[10px] text-zinc-400 opacity-0 group-hover:opacity-100 hover:text-emerald-400 disabled:opacity-30">edit &amp; resend</button>
              )}
              {item.kind === "assistant" && item.provider && (
                <div className="mt-1 text-[10px] uppercase tracking-wide text-zinc-500">via {item.provider}{item.model ? ` · ${item.model}` : ""}</div>
              )}
            </div>
          );
        })}
        {busy && <p className="animate-pulse text-xs text-zinc-500">thinking…</p>}
      </div>

      {hasUser && (
        <div className="flex justify-end border-t border-zinc-800 px-3 pt-2">
          <button onClick={retryLast} disabled={busy} className="text-[11px] text-zinc-400 hover:text-emerald-400 disabled:opacity-40">↻ Retry last response</button>
        </div>
      )}

      <div className="flex gap-2 border-t border-zinc-800 p-3">
        <textarea
          ref={inputRef}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              send();
            }
          }}
          rows={1}
          placeholder="Message JARVIS…  (Enter to send, Shift+Enter for newline)"
          aria-label="Chat message"
          className="max-h-40 flex-1 resize-none rounded-md bg-zinc-900 px-3 py-2 text-sm outline-none ring-emerald-500 focus:ring-2"
        />
        <button onClick={send} disabled={busy || !input.trim()} className="self-end rounded-md bg-emerald-600 px-4 py-2 text-sm font-medium disabled:opacity-50">Send</button>
      </div>
    </div>
  );
}
