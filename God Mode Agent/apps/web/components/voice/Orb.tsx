"use client";

// Voice orb per JARVIS_UI_LOCKED_SPEC.md §4. HTML5 Canvas. All colors come
// from the .voice-root CSS custom properties (no hardcoded hex here).
// Amplitude is real: an AnalyserNode (mic while listening, TTS graph while
// speaking) supplies FFT bins; RMS of bins 2..96 / 86, clamped 0..1,
// lerped 0.16/frame. No simulated amplitude while a live stream exists.

import { useEffect, useRef } from "react";
import type { OrbState } from "@/lib/voiceTypes";

type Props = {
  state: OrbState;
  analyserRef: React.MutableRefObject<AnalyserNode | null>;
  onPressStart: () => void;
  onPressEnd: () => void;
};

type Tokens = {
  cyan: string; cyanHot: string; amber: string; ink: string;
};

function readTokens(el: HTMLElement): Tokens {
  const s = getComputedStyle(el);
  return {
    cyan: s.getPropertyValue("--cyan").trim(),
    cyanHot: s.getPropertyValue("--cyan-hot").trim(),
    amber: s.getPropertyValue("--amber").trim(),
    ink: s.getPropertyValue("--ink").trim(),
  };
}

function withAlpha(hex: string, a: number): string {
  const h = hex.replace("#", "");
  const n = parseInt(h, 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}

const ARC_SPEEDS = [0.3, 0.3, -0.44, 0.62, 0.62];
const ARC_RADII = [0.735, 0.685, 0.545, 0.735, 0.685];
const TICKS = 144;
const SCOPE_POINTS = 180;

export default function Orb({ state, analyserRef, onPressStart, onPressEnd }: Props) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const stateRef = useRef<OrbState>(state);
  stateRef.current = state;

  useEffect(() => {
    const canvas = canvasRef.current;
    const wrap = wrapRef.current;
    if (!canvas || !wrap) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const tokens = readTokens(wrap);
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const freq = new Uint8Array(256); // fftSize 512 -> 256 bins

    let raf = 0;
    let amp = 0;
    let t0 = performance.now();
    let elapsed = 0;

    const resize = () => {
      // Canvas is sized from the orb wrapper box, never from the page (§1).
      const rect = wrap.getBoundingClientRect();
      const side = Math.floor(Math.min(rect.width, rect.height));
      const dpr = window.devicePixelRatio || 1;
      canvas.style.width = `${side}px`;
      canvas.style.height = `${side}px`;
      canvas.width = side * dpr;
      canvas.height = side * dpr;
    };
    resize();
    const ro = new ResizeObserver(resize);
    ro.observe(wrap);

    const draw = (now: number) => {
      raf = requestAnimationFrame(draw);
      const dt = Math.min((now - t0) / 1000, 0.1);
      t0 = now;
      if (!reducedMotion) elapsed += dt;

      const st = stateRef.current;
      const analyser = analyserRef.current;
      const audioLive = analyser && (st === "listening" || st === "speaking");
      if (audioLive) analyser.getByteFrequencyData(freq);

      // amplitude target: real audio when live; state-driven envelope otherwise
      let target = 0;
      if (audioLive) {
        let sum = 0;
        for (let i = 2; i < 96; i++) sum += freq[i] * freq[i];
        target = Math.min(Math.sqrt(sum / 94) / 86, 1);
      } else if (st === "thinking") {
        target = 0.16 + 0.05 * Math.sin(elapsed * 2 * Math.PI * 1.6); // steady low pulse
      } else {
        target = 0.08 + 0.06 * Math.sin(elapsed * 2 * Math.PI * 0.9); // breathe 0.9 Hz
      }
      amp += (target - amp) * 0.16;

      const dpr = window.devicePixelRatio || 1;
      const w = canvas.width / dpr;
      const cx = w / 2;
      const R = w / 2;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, w, w);

      const ring = st === "thinking" ? tokens.amber : st === "listening" ? tokens.cyanHot : tokens.cyan;
      const ringAlpha = st === "standby" ? 0.35 : 0.75;

      // 1. outer tick ring — 144 radial ticks 0.815R→0.90R, FFT-modulated
      ctx.save();
      ctx.translate(cx, cx);
      ctx.rotate(elapsed * 0.06);
      ctx.strokeStyle = withAlpha(ring, ringAlpha * 0.8);
      ctx.lineWidth = 1;
      for (let i = 0; i < TICKS; i++) {
        const a = (i / TICKS) * Math.PI * 2;
        const bin = audioLive ? freq[(i * 2) % 192] / 255 : amp;
        const inner = 0.815 * R;
        const outer = (0.9 - 0.035 * (1 - bin)) * R;
        ctx.beginPath();
        ctx.moveTo(Math.cos(a) * inner, Math.sin(a) * inner);
        ctx.lineTo(Math.cos(a) * outer, Math.sin(a) * outer);
        ctx.stroke();
      }
      ctx.restore();

      // 2. hairline circles at 0.795R / 0.62R / 0.455R
      [[0.795, 0.1], [0.62, 0.14], [0.455, 0.17]].forEach(([r, a]) => {
        ctx.beginPath();
        ctx.strokeStyle = withAlpha(ring, a);
        ctx.lineWidth = 1;
        ctx.arc(cx, cx, r * R, 0, Math.PI * 2);
        ctx.stroke();
      });

      // 3. counter-rotating arc set
      ctx.lineCap = "round";
      ARC_SPEEDS.forEach((speed, i) => {
        const start = elapsed * speed + i * 1.7;
        ctx.beginPath();
        ctx.strokeStyle = withAlpha(ring, 0.5);
        ctx.lineWidth = 2;
        ctx.arc(cx, cx, ARC_RADII[i] * R, start, start + 0.6 + 0.25 * (i % 3));
        ctx.stroke();
      });

      // 4. circular oscilloscope at 0.365R (listening/speaking only)
      if (audioLive) {
        ctx.beginPath();
        ctx.strokeStyle = withAlpha(st === "listening" ? tokens.cyanHot : tokens.ink, 0.8);
        ctx.lineWidth = 1.5;
        for (let i = 0; i <= SCOPE_POINTS; i++) {
          const a = (i / SCOPE_POINTS) * Math.PI * 2;
          const bin = freq[(i * Math.floor(192 / SCOPE_POINTS)) % 192] / 255;
          const r = (0.365 + 0.05 * bin) * R;
          const x = cx + Math.cos(a) * r;
          const y = cx + Math.sin(a) * r;
          if (i === 0) ctx.moveTo(x, y);
          else ctx.lineTo(x, y);
        }
        ctx.closePath();
        ctx.stroke();
      }

      // 5. arc-reactor core — base 0.235R, +0.10R × amplitude
      const coreR = (0.235 + 0.1 * amp) * R;
      const core = st === "speaking" ? tokens.ink : ring;
      const grad = ctx.createRadialGradient(cx, cx, 0, cx, cx, coreR);
      grad.addColorStop(0, withAlpha(tokens.ink, 0.95));
      grad.addColorStop(0.35, withAlpha(core, 0.55));
      grad.addColorStop(1, withAlpha(core, 0));
      ctx.fillStyle = grad;
      ctx.beginPath();
      ctx.arc(cx, cx, coreR, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = withAlpha(tokens.ink, 0.9);
      ctx.beginPath();
      ctx.arc(cx, cx, coreR * 0.3, 0, Math.PI * 2);
      ctx.fill();

      // 6. crosshair hairlines 0.90R→0.99R
      ctx.strokeStyle = withAlpha(ring, 0.4);
      ctx.lineWidth = 1;
      [0, Math.PI / 2, Math.PI, (3 * Math.PI) / 2].forEach((a) => {
        ctx.beginPath();
        ctx.moveTo(cx + Math.cos(a) * 0.9 * R, cx + Math.sin(a) * 0.9 * R);
        ctx.lineTo(cx + Math.cos(a) * 0.99 * R, cx + Math.sin(a) * 0.99 * R);
        ctx.stroke();
      });
    };

    raf = requestAnimationFrame(draw);
    return () => {
      cancelAnimationFrame(raf);
      ro.disconnect();
    };
  }, [analyserRef]);

  return (
    <div ref={wrapRef} className="flex h-full w-full items-center justify-center">
      <canvas
        ref={canvasRef}
        role="button"
        aria-label="Push to talk — hold to speak"
        onPointerDown={onPressStart}
        onPointerUp={onPressEnd}
        onPointerLeave={onPressEnd}
        className="cursor-pointer touch-none select-none"
      />
    </div>
  );
}
