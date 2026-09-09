"use client";

// Clean-room arc-reactor HUD. Original Canvas2D implementation; no third-party
// code or assets. Renders concentric rotating rings, drifting particles, and a
// pulsing core; animation speed/pulse are driven by `state`.

import { useEffect, useRef } from "react";
import { JARVIS } from "@/lib/theme";

export type HudState = "idle" | "listening" | "processing" | "speaking";

function hexA(hex: string, a: number): string {
  const h = hex.replace("#", "");
  const n = parseInt(h, 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}

function stateLabel(s: HudState): string {
  return s === "listening" ? "listening" : s === "processing" ? "processing" : s === "speaking" ? "speaking" : "standby";
}

function drawRing(
  ctx: CanvasRenderingContext2D,
  cx: number,
  cy: number,
  r: number,
  rot: number,
  ticks: number,
  color: string,
  alpha: number,
): void {
  if (r <= 0) return;
  ctx.save();
  ctx.translate(cx, cy);
  ctx.rotate(rot);
  ctx.strokeStyle = hexA(color, alpha);
  ctx.lineWidth = 1.5;
  ctx.beginPath();
  ctx.arc(0, 0, r, 0, Math.PI * 2);
  ctx.stroke();
  ctx.strokeStyle = hexA(color, alpha * 0.8);
  for (let i = 0; i < ticks; i++) {
    const a = (i / ticks) * Math.PI * 2;
    ctx.beginPath();
    ctx.moveTo(Math.cos(a) * (r - 4), Math.sin(a) * (r - 4));
    ctx.lineTo(Math.cos(a) * (r + 4), Math.sin(a) * (r + 4));
    ctx.stroke();
  }
  ctx.restore();
}

export default function HudCanvas({ state = "idle", compact = false }: { state?: HudState; compact?: boolean }) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const stateRef = useRef<HudState>(state);
  stateRef.current = state;

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    let raf = 0;
    let running = true;
    let t = 0;

    const resize = () => {
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const rect = canvas.getBoundingClientRect();
      canvas.width = Math.max(1, Math.floor(rect.width * dpr));
      canvas.height = Math.max(1, Math.floor(rect.height * dpr));
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    };
    resize();
    const ro = new ResizeObserver(resize);
    ro.observe(canvas);

    const draw = () => {
      if (!running) return;
      try {
        const rect = canvas.getBoundingClientRect();
        const w = rect.width;
        const h = rect.height;
        const cx = w / 2;
        const cy = h / 2;
        const R = Math.min(w, h) / 2 - 6;
        ctx.clearRect(0, 0, w, h);
        if (R <= 4) {
          t += 1;
          raf = requestAnimationFrame(draw);
          return;
        }
        const st = stateRef.current;
        const speed = st === "processing" ? 2.2 : st === "listening" ? 1.4 : st === "speaking" ? 1.0 : 0.6;
        const pulse = st === "speaking" ? Math.sin(t * 0.15) * 0.5 + 0.5 : 0;

        const glow = ctx.createRadialGradient(cx, cy, 0, cx, cy, R);
        glow.addColorStop(0, hexA(JARVIS.accent, 0.2 + pulse * 0.15));
        glow.addColorStop(1, "rgba(0,0,0,0)");
        ctx.fillStyle = glow;
        ctx.beginPath();
        ctx.arc(cx, cy, R, 0, Math.PI * 2);
        ctx.fill();

        drawRing(ctx, cx, cy, R * 0.92, t * 0.01 * speed, 22, JARVIS.accent, 0.5);
        drawRing(ctx, cx, cy, R * 0.72, -t * 0.015 * speed, 14, JARVIS.accent, 0.35);
        drawRing(ctx, cx, cy, R * 0.55, t * 0.02 * speed, 8, JARVIS.accentDim, 0.5);

        const coreR = R * (0.16 + pulse * 0.06);
        const core = ctx.createRadialGradient(cx, cy, 0, cx, cy, coreR);
        core.addColorStop(0, hexA(JARVIS.accent, 0.95));
        core.addColorStop(1, hexA(JARVIS.accent, 0.05));
        ctx.fillStyle = core;
        ctx.beginPath();
        ctx.arc(cx, cy, coreR, 0, Math.PI * 2);
        ctx.fill();

        ctx.fillStyle = hexA(JARVIS.accent, 0.6);
        for (let i = 0; i < 24; i++) {
          const a = (i / 24) * Math.PI * 2 + t * 0.003 * speed;
          const rad = R * (0.6 + 0.35 * Math.sin(i * 1.7 + t * 0.02));
          ctx.beginPath();
          ctx.arc(cx + Math.cos(a) * rad, cy + Math.sin(a) * rad, 1.2, 0, Math.PI * 2);
          ctx.fill();
        }
      } catch {
        /* never let a paint error crash the app */
      }
      t += 1;
      raf = requestAnimationFrame(draw);
    };
    raf = requestAnimationFrame(draw);

    const onVis = () => {
      if (document.hidden) {
        running = false;
        cancelAnimationFrame(raf);
      } else if (!running) {
        running = true;
        raf = requestAnimationFrame(draw);
      }
    };
    document.addEventListener("visibilitychange", onVis);

    return () => {
      running = false;
      cancelAnimationFrame(raf);
      ro.disconnect();
      document.removeEventListener("visibilitychange", onVis);
    };
  }, []);

  return (
    <div
      className="relative w-full overflow-hidden rounded-lg border border-zinc-800"
      style={{ height: compact ? 72 : 168, background: JARVIS.bg }}
    >
      <canvas ref={canvasRef} className="h-full w-full" aria-hidden="true" />
      <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
        <span className="text-[10px] uppercase tracking-[0.3em] text-cyan-300/70">{stateLabel(state)}</span>
      </div>
    </div>
  );
}
