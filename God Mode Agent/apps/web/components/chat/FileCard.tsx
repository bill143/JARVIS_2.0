"use client";

export function FileCard({
  name,
  size,
  progress,
  status,
  href,
  error,
}: {
  name: string;
  size: number;
  progress: number;
  status: "uploading" | "success" | "error";
  href?: string;
  error?: string;
}) {
  const sizeLabel = size >= 1024 * 1024 ? `${(size / (1024 * 1024)).toFixed(1)} MB` : `${Math.max(1, Math.round(size / 1024))} KB`;
  const done = status === "success";
  const failed = status === "error";

  return (
    <div className="rounded-xl border border-[var(--jarvis-border)] bg-[var(--jarvis-surface)] p-3">
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <div className="truncate text-sm font-medium text-[var(--jarvis-text)]">{name}</div>
          <div className="text-[11px] text-[var(--jarvis-muted)]">{sizeLabel}</div>
        </div>
        <div className="text-[11px] font-medium text-[var(--jarvis-muted)]">
          {done ? "✓" : failed ? "ERR" : `${Math.round(progress)}%`}
        </div>
      </div>
      <div className="mt-2 h-2 overflow-hidden rounded-full bg-[var(--jarvis-bg)]">
        <div
          className={`h-full rounded-full transition-all ${
            done ? "bg-[var(--jarvis-success)]" : failed ? "bg-[var(--jarvis-error)]" : "bg-[var(--jarvis-primary)]"
          }`}
          style={{ width: `${Math.min(100, Math.max(0, progress))}%` }}
        />
      </div>
      {error && <div className="mt-2 text-[11px] text-[var(--jarvis-error)]">{error}</div>}
      {href && done && (
        <div className="mt-2">
          <a className="text-[11px] text-[var(--jarvis-accent)] underline" href={href} target="_blank" rel="noreferrer">
            Download
          </a>
        </div>
      )}
    </div>
  );
}
