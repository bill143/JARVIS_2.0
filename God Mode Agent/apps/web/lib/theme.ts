// JARVIS control-center design tokens.
//
// Clean-room: these are original values (deep-navy + cyan HUD aesthetic), not
// copied from any third-party repo. Reconcile with the local `jarvis-design-system`
// skill before final polish — where that skill specifies values, it wins.

export const JARVIS = {
  bg: "#0a0e14",
  surface: "#111826",
  surfaceAlt: "#0d1420",
  border: "#1e2a3a",
  text: "#e5e7eb",
  muted: "#94a3b8",
  accent: "#38bdf8", // cyan HUD accent
  accentDim: "#0e7490",
  success: "#34d399",
  warn: "#fbbf24",
  error: "#f87171",
} as const;

const ACCENT_PREF_KEY = "jarvis.accentHue";

function hexToHsl(hex: string): [number, number, number] {
  const h = hex.replace("#", "");
  const n = parseInt(h.length === 3 ? h.replace(/(.)/g, "$1$1") : h, 16);
  const r = ((n >> 16) & 255) / 255;
  const g = ((n >> 8) & 255) / 255;
  const b = (n & 255) / 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  let hue = 0;
  const l = (max + min) / 2;
  const d = max - min;
  const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
  if (d !== 0) {
    if (max === r) hue = ((g - b) / d) % 6;
    else if (max === g) hue = (b - r) / d + 2;
    else hue = (r - g) / d + 4;
    hue *= 60;
    if (hue < 0) hue += 360;
  }
  return [hue, s, l];
}

function hslToHex(h: number, s: number, l: number): string {
  const c = (1 - Math.abs(2 * l - 1)) * s;
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1));
  const m = l - c / 2;
  let r = 0;
  let g = 0;
  let b = 0;
  if (h < 60) [r, g, b] = [c, x, 0];
  else if (h < 120) [r, g, b] = [x, c, 0];
  else if (h < 180) [r, g, b] = [0, c, x];
  else if (h < 240) [r, g, b] = [0, x, c];
  else if (h < 300) [r, g, b] = [x, 0, c];
  else [r, g, b] = [c, 0, x];
  const to = (v: number) => Math.round((v + m) * 255).toString(16).padStart(2, "0");
  return `#${to(r)}${to(g)}${to(b)}`;
}

/** Set the accent family to a chosen hue (status colors stay fixed). UI pref only. */
export function applyAccentHue(hex: string): void {
  if (typeof document === "undefined") return;
  const root = document.documentElement;
  const [h, s, l] = hexToHsl(hex);
  root.style.setProperty("--jarvis-accent", hex);
  root.style.setProperty("--jarvis-accent-dim", hslToHex(h, s, Math.max(0, l - 0.25)));
  try {
    localStorage.setItem(ACCENT_PREF_KEY, hex);
  } catch {
    /* prefs are best-effort */
  }
}

export function loadAccentHue(): string | null {
  try {
    return localStorage.getItem(ACCENT_PREF_KEY);
  } catch {
    return null;
  }
}
