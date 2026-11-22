"use client";

import { useEffect, useRef } from "react";

export type ChatBubble = {
  id: string;
  kind: "user" | "assistant" | "system";
  text: string;
  provider?: string;
  model?: string;
};

export function ChatSurface({
  items,
  busy,
  onRetryLast,
  onEditResend,
}: {
  items: ChatBubble[];
  busy: boolean;
  onRetryLast?: () => void;
  onEditResend?: (index: number) => void;
}) {
  const listRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const node = listRef.current;
    if (!node) return;
    node.scrollTop = node.scrollHeight;
  }, [items, busy]);

  return (
    <div className="flex h-[70vh] flex-col rounded-xl border border-[var(--jarvis-border)] bg-[var(--jarvis-surface)] shadow-[0_0_18px_rgba(15,118,110,0.08)]">
      <div ref={listRef} className="flex-1 space-y-3 overflow-y-auto p-4">
        {items.length === 0 && (
          <p className="text-sm text-[var(--jarvis-muted)]">
            Try: <code className="text-[var(--jarvis-accent)]">compute 2+2</code>, or ask for a status update.
          </p>
        )}

        {items.map((item, index) => {
          if (item.kind === "system") {
            return (
              <div key={item.id} className="flex justify-center">
                <div className="rounded-full border border-[var(--jarvis-border)] bg-[var(--jarvis-bg)] px-3 py-1 text-[11px] uppercase tracking-[0.18em] text-[var(--jarvis-muted)]">
                  {item.text}
                </div>
              </div>
            );
          }

          const isUser = item.kind === "user";
          return (
            <div key={item.id} className={`group max-w-[85%] ${isUser ? "ml-auto" : "mr-auto"}`}>
              <div
                className={`rounded-2xl border px-3 py-2 text-sm leading-relaxed shadow-sm ${
                  isUser
                    ? "border-[var(--jarvis-border)] bg-[var(--jarvis-surface-strong)] text-[var(--jarvis-text)]"
                    : "border-[var(--jarvis-primary)]/30 bg-[var(--jarvis-primary)]/10 text-[var(--jarvis-text)]"
                }`}
              >
                <div className="whitespace-pre-wrap">{item.text}</div>
                {item.provider && (
                  <div className="mt-2 text-[10px] uppercase tracking-[0.14em] text-[var(--jarvis-muted)]">
                    via {item.provider}
                    {item.model ? ` · ${item.model}` : ""}
                  </div>
                )}
              </div>
              {isUser && onEditResend && (
                <div className="mt-1 flex justify-end">
                  <button
                    type="button"
                    onClick={() => onEditResend(index)}
                    className="text-[10px] text-[var(--jarvis-muted)] hover:text-[var(--jarvis-accent)]"
                    aria-label="Edit and resend message"
                  >
                    edit &amp; resend
                  </button>
                </div>
              )}
            </div>
          );
        })}

        {busy && <p className="text-xs text-[var(--jarvis-muted)]">thinking…</p>}
      </div>

      {onRetryLast && (
        <div className="flex justify-end border-t border-[var(--jarvis-border)] px-3 py-2">
          <button
            type="button"
            onClick={onRetryLast}
            disabled={busy}
            className="text-[11px] text-[var(--jarvis-muted)] hover:text-[var(--jarvis-accent)] disabled:opacity-40"
            aria-label="Retry last response"
          >
            ↻ Retry last response
          </button>
        </div>
      )}
    </div>
  );
}
