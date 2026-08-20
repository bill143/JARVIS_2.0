"use client";

import { useRef } from "react";

export function Composer({
  value,
  busy,
  recording,
  status,
  onChange,
  onSend,
  onAttach,
  onMicToggle,
  onWake,
}: {
  value: string;
  busy: boolean;
  recording: boolean;
  status: "active" | "sleeping";
  onChange: (value: string) => void;
  onSend: () => void;
  onAttach: (files: FileList | null) => void;
  onMicToggle: () => void;
  onWake?: () => void;
}) {
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  return (
    <div className="rounded-xl border border-[var(--jarvis-border)] bg-[var(--jarvis-surface)] p-3">
      <input
        ref={fileInputRef}
        type="file"
        multiple
        className="hidden"
        onChange={(event) => {
          onAttach(event.target.files);
          event.target.value = "";
        }}
        aria-label="Attach files"
      />

      <div className="flex gap-2">
        <textarea
          value={value}
          onChange={(event) => onChange(event.target.value)}
          rows={1}
          placeholder="Message JARVIS…"
          aria-label="Message input"
          onKeyDown={(event) => {
            if (event.key === "Enter" && !event.shiftKey) {
              event.preventDefault();
              onSend();
            }
          }}
          className="min-h-[56px] flex-1 resize-none rounded-xl border border-[var(--jarvis-border)] bg-[var(--jarvis-bg)] px-3 py-2 text-sm text-[var(--jarvis-text)] outline-none ring-0 placeholder:text-[var(--jarvis-muted)] focus:border-[var(--jarvis-primary)]"
        />
      </div>

      <div className="mt-2 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <button
            type="button"
            aria-label="Attach files"
            onClick={() => fileInputRef.current?.click()}
            className="rounded-lg border border-[var(--jarvis-border)] bg-[var(--jarvis-bg)] px-3 py-2 text-xs text-[var(--jarvis-text)] hover:border-[var(--jarvis-primary)]"
          >
            Attach
          </button>
          <button
            type="button"
            aria-label={recording ? "Stop microphone" : "Start microphone"}
            onClick={onMicToggle}
            className={`rounded-lg px-3 py-2 text-xs font-medium ${
              recording ? "bg-[var(--jarvis-error)] text-white shadow-[0_0_14px_rgba(239,68,68,0.5)]" : "bg-[var(--jarvis-surface-strong)] text-[var(--jarvis-text)]"
            }`}
          >
            {recording ? "Mic on" : "Mic"}
          </button>
          {status === "sleeping" && onWake && (
            <button
              type="button"
              aria-label="Wake assistant"
              onClick={onWake}
              className="rounded-lg bg-[var(--jarvis-accent)] px-3 py-2 text-xs font-semibold text-slate-950"
            >
              WAKE
            </button>
          )}
        </div>

        <button
          type="button"
          aria-label="Send message"
          onClick={onSend}
          disabled={busy || value.trim().length === 0}
          className="rounded-lg bg-[var(--jarvis-primary)] px-4 py-2 text-xs font-semibold text-slate-950 disabled:cursor-not-allowed disabled:opacity-50"
        >
          Send
        </button>
      </div>
    </div>
  );
}
