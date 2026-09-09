# JARVIS VOICE INTERFACE — LOCKED LAYOUT SPEC v1.0
**Paste this entire file into Claude Code as the FIRST message of the task.**
Do not paraphrase it. Do not summarize it. The constraint tables are the contract.

---

## 0. WHY THIS FILE EXISTS

Previous attempts drifted back into an admin-dashboard layout because the instruction
was adjectival ("make it feel like JARVIS") instead of dimensional. This spec replaces
adjectives with **pixel budgets, percentage ceilings, and a forbidden-element list.**
Any output that violates a MUST NOT rule is rejected without review.

Product identity: **a voice presence you talk to.** Not a chat client. Not a console.
Text is the fallback path, never the default path.

---

## 1. SCREEN BUDGET — HARD NUMBERS (non-negotiable)

| Region | Budget | Rule |
|---|---|---|
| Voice orb (center canvas) | **≥ 55% of the smaller viewport dimension** | The single dominant element on screen. |
| Persistent left navigation | **0 px** | Deleted. Does not exist in the voice route. |
| KPI / metrics sidebar | **0 px** | Deleted. Telemetry becomes corner text. |
| Chat transcript, visible by default | **0 px** | Opt-in overlay only, keyboard `T`. |
| Text input, visible by default | **0 px** | Slides up on `/` keypress, then retracts. |
| Peripheral telemetry text | **≤ 4 lines per corner, ≤ 11 px, ≤ 30% opacity** | No borders, no cards, no backgrounds. |
| Bottom control rail | **≤ 44 px tall, hairline top border only** | Text buttons. No filled buttons. |
| Caption band under orb | **min-height 132 px** | Reserved so text never overlaps the orb. |

**Vertical layout is a 3-row CSS grid on the `<main>` element and nothing else:**
```
grid-template-rows: 1fr auto auto;
  row 1  →  orb wrapper (canvas sized from THIS box, never from the page)
  row 2  →  state label + sub-label
  row 3  →  caption band
```

---

## 2. FORBIDDEN — AUTOMATIC REJECTION

The build is rejected on sight if ANY of these appear in the voice route:

- A left sidebar with a vertical list of page links
- A right-hand drawer that holds the microphone controls
- A message bubble with a background fill or a rounded rectangle
- A `<div>` with `border` + `border-radius` + `padding` wrapping telemetry (a "card")
- An always-visible text input at the bottom of the screen
- A letterboxed strip or panel with static placeholder text
- Emerald / mint / `#10B981` as the primary accent
- Any element that renders larger on screen than the orb
- Filled solid-color buttons anywhere in the voice route
- Spinners; use the orb state machine instead
- Chat history rendered inline in the main column

---

## 3. STATE MACHINE — EXACTLY FOUR STATES

| State | Trigger | Ring color | Core | Label | Sub-label |
|---|---|---|---|---|---|
| `standby` | idle | `#22D3EE` @ low alpha | slow breathe, 0.9 Hz | STANDBY | hold space to speak |
| `listening` | space held / orb pressed | `#8FF3FF` | mic amplitude drives radius | LISTENING | release to send |
| `thinking` | request in flight | `#F5A524` | steady low pulse | PROCESSING | reasoning |
| `speaking` | TTS playing | `#22D3EE` / white core | TTS amplitude drives radius | SPEAKING | esc to interrupt |

There is no fifth state. There is no "error" screen — errors are a 9 px amber strip
that slides down from the top edge for 4.2 s and then retracts.

---

## 4. ORB RENDER SPEC (HTML5 Canvas, not SVG, not CSS)

Draw order, back to front:

1. **Outer tick ring** — 144 radial ticks at 0.815R → 0.90R. Tick length modulated by
   `AnalyserNode` frequency bin. Rotation 0.06 rad/s.
2. **Hairline circles** — at 0.795R, 0.62R, 0.455R. Alpha 0.10 → 0.17.
3. **Counter-rotating arc set** — 5 arcs at 0.735R / 0.685R / 0.545R,
   speeds `+0.30, +0.30, -0.44, +0.62, +0.62` rad/s, `lineCap: round`.
4. **Circular oscilloscope** — 180-point closed path at 0.365R, only in
   `listening` and `speaking`, radius offset from live FFT bins.
5. **Arc reactor core** — radial gradient, base radius `0.235R`,
   expands by `+0.10R × amplitude`. White inner disc at `0.30 × core radius`.
6. **Crosshair hairlines** — 4 tick marks at 0.90R → 0.99R.

Audio is **real**: `getUserMedia` → `AnalyserNode` (fftSize 512,
smoothing 0.72) → RMS of bins 2..96, normalized `/86`, clamped 0..1.
Amplitude is lerped at `0.16` per frame. Never simulate amplitude with `Math.random()`
while a live mic stream exists.

---

## 5. INPUT MODEL

| Action | Binding |
|---|---|
| Push-to-talk | Hold `Space`, or `pointerdown` on the orb |
| Send | Release |
| Interrupt / barge-in | `Esc` — cancels TTS immediately |
| Text fallback | `/` opens the hairline input; `Enter` sends; `Esc` retracts |
| Transcript overlay | `T` toggles; `Esc` closes |
| Systems overlay | `S` toggles |

Orb hit target is the full canvas. Rail buttons are secondary paths to the same handlers.

---

## 6. DESIGN TOKENS (CSS custom properties — no hardcoded hex in components)

```css
--bg:#000308;  --ink:#E8FBFF;  --ink-2:#7FA8B4;  --ink-3:#3C5B66;
--cyan:#22D3EE; --cyan-hot:#8FF3FF; --amber:#F5A524; --red:#FF4D4D;
--hair:rgba(34,211,238,.16); --hair-2:rgba(34,211,238,.08);
```

Type: `Syne` 800 for the wordmark · `Outfit` 300 for spoken captions ·
`JetBrains Mono` for every number, label, status, and keycap.
All telemetry labels are `uppercase` with `letter-spacing: .13em – .28em`.

---

## 7. WHERE THE OTHER 19 PAGES GO

Vision, Memory, Tools/Logs, Planner, Agents, Knowledge, Integrations, Memory Gov, Cost,
Policy, Approvals, Audit, Compliance, Evals, Health, Settings **do not get a sidebar.**

They move to a **command palette** (`Ctrl + K`) and a full-screen `S` systems overlay.
The voice route stays at `/`. The console lives at `/console` for the rare occasion
Bill needs a table. Two routes. Never blended into one screen.

---

## 8. ACCEPTANCE CHECKLIST — verify before reporting complete

- [ ] Screenshot at 1440×900: orb diameter measures ≥ 495 px
- [ ] Screenshot at 1440×900: zero left-nav pixels
- [ ] No text overlaps the orb glow at any viewport from 360 px to 2560 px wide
- [ ] Holding `Space` visibly deforms the tick ring in response to real voice
- [ ] `Esc` cuts TTS mid-sentence in under 100 ms
- [ ] `prefers-reduced-motion: reduce` freezes all rotation, orb still renders
- [ ] Caption text passes 4.5:1 contrast against `#000308`
- [ ] No `#10B981`, no filled buttons, no bordered cards anywhere in the route
- [ ] Zero `TODO`, zero placeholder strings, zero disabled auth paths

---

## 9. THE PROMPT TO USE (copy this line verbatim after pasting the spec)

> Implement JARVIS_UI_LOCKED_SPEC.md exactly. Treat every MUST NOT in section 2 as a
> compile error. Before you write code, restate section 1's screen budget table back to
> me as numbers. After you build, run the section 8 acceptance checklist and paste the
> results. Do not propose alternatives. Do not add features not in this spec.
