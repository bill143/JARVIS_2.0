# CLAUDE IMPLEMENTATION PROMPT — JARVIS Control-Center UI (CLEAN-ROOM)

Copy everything below the line into a fresh Claude Code session opened at
`C:\dev\JARVIS_2.0\God Mode Agent`. This **replaces** the earlier
`docs/ui-extraction/CLAUDE_IMPLEMENTATION_PROMPT.md`, which was withdrawn for
license reasons (see the LEGAL GUARDRAIL below).

---

You are implementing a "JARVIS control center" UI upgrade in this repo
(`apps/web`, Next.js 14.2.35 + React 18, no component library). Build it
**clean-room, from this behavioral specification only.**

## LEGAL GUARDRAIL — read first, non-negotiable

The reference repos surveyed earlier are **not usable as code sources** for this
commercial product:

- `FatihMakes/Mark-L` and `FatihMakes/Mark-XXXIX-OR` — **CC BY-NC 4.0
  (non-commercial only)**.
- `AnubhavChaturvedi-GitHub/jarvis-ai-assistant` — **GPL-3.0 (copyleft), with a
  contradictory MIT badge**.

Therefore, for every file you create:

- **Do NOT clone, open, fetch, or read any of those three repositories.** Do not
  reference their files, line ranges, CSS values, markup, or palette.
- **Do NOT copy or "adapt" their code.** Copyright covers their specific
  expression; it does not cover the general UI *concepts* below, which you will
  implement from scratch.
- **Do NOT add any `// Ported from <repo>…` provenance header.** These are your
  own original files.
- Treat `docs/ui-extraction/UI_REPO_AUDIT.md` and `JARVIS_UI_BEST_OF_PLAN.md`
  only as a *feature/behavior checklist and target IA*. **Ignore every
  "copy/adapt from <repo> l.xxx" instruction and every line-range in them.**
- Source all visual values (colors, radii, spacing, motion) from the
  **`jarvis-design-system` skill** (load it before styling anything) and the
  repo's existing `app/globals.css`. Where the skill is silent, choose your own
  values. Never reproduce a third-party palette.

If anything here would require reading those repos, stop and implement the
behavior generically instead.

## Setup

- Work on a new branch `feat/jarvis-control-center` off `feat/god-mode-agent`.
  First run `git pull` so you are on the latest head.
- Do not add a component library or CSS framework. Do not touch `apps/api`
  except the explicitly listed additive endpoints.
- Reuse the existing surfaces: `lib/api.ts` (`getJson/postJson/putJson`,
  `wsUrl`), `lib/auth.tsx` (`useAuth`, `hasRole`), `app/page.tsx` (the
  `PANELS` rail with `assistant`/`governance` groups), `components/StatusStrip.tsx`,
  and the existing chat WS at `/realtime/chat` and voice WS at `/realtime/voice`.

## Phase 1 — Foundation (no visual regression)

Create:
- `apps/web/lib/theme.ts` — a token map (background/surface/primary/accent/
  radius/spacing/status) with values from the `jarvis-design-system` skill, and
  `applyAccentHue(hex: string)` that hue-rotates only the hue-linked tokens while
  leaving status colors (success/warn/error) fixed. Persist the chosen hue as a
  **UI preference only** (localStorage is fine for prefs; never store auth there).
- `apps/web/lib/ws.ts` — a discriminated-union message type
  `{type:"log",speaker,text} | {type:"status",state:"active"|"sleeping"} |
  {type:"sys",text} | {type:"file_received",name,size} |
  {type:"metrics",cpu,ram,gpu,netUp,netDown}` and a `useJarvisSocket(url)` hook
  with exponential-backoff reconnect and a "reconnecting" state. No `any`.

Modify:
- `apps/web/app/globals.css` — add CSS custom properties for every token in
  `theme.ts`. Do not remove existing rules.

## Phase 2 — Chat surface

Create (compose into the existing `ChatTab.tsx`, preserving its `/realtime/chat`
+ `/chat` data flow and the retry/edit-resend behavior already there):
- `components/chat/ChatSurface.tsx` — message feed with three bubble styles:
  assistant (accent-tinted), user (surface-tinted, right-aligned), system
  (centered, muted). Auto-scroll to newest; subtle pop-in.
- `components/chat/Composer.tsx` — input row: multiline textarea (Enter sends,
  Shift+Enter newline), attach button (hidden multiple file input), mic button
  (recording = red pulse), WAKE button (visible only when status=sleeping), send.
- `components/chat/FileCard.tsx` — upload card: icon-by-extension, name, size,
  progress bar, ✓/ERR state, download-back link.
- `apps/web/lib/upload.ts` — `uploadFile(file, onProgress)` via `XMLHttpRequest`
  for progress events, POSTing to an **additive** `POST /uploads` and reading back
  `GET /uploads/{name}` on `apps/api/jarvis_api` (implement these using God Mode's
  existing auth dependency + audit; if you prefer, gate behind a new
  `documents`-style module). If the endpoint is absent, the card shows a clear
  "upload endpoint not enabled" message — never a silent failure.

## Phase 3 — HUD and system rail

Create:
- `components/hud/HudCanvas.tsx` — a `<canvas>` + `requestAnimationFrame`
  "arc reactor": concentric rotating rings, drifting particles, center glow.
  Prop `state: "idle" | "listening" | "processing" | "speaking"` (speaking pulses
  the ring radius). Pause rAF when the tab is hidden (`visibilitychange`).
  Implement the paint math yourself from the Canvas2D API.
- `components/hud/MetricBar.tsx` — labeled thin bar + value text; color shifts to
  warn >70%, error >90% (use the fixed status tokens).
- `components/hud/SystemRail.tsx` — stacks MetricBars for CPU/RAM/GPU/NET fed by
  `{type:"metrics"}` WS messages; if no metrics arrive, render "—" placeholders.

Modify:
- `apps/web/app/page.tsx` — the center column renders `HudCanvas` above the chat
  surface; the HUD collapses to a compact strip when an assistant panel is open.
  Mount `SystemRail` in the left rail above the panel nav.
- `apps/api/jarvis_api` — **additive** `GET /system/metrics` (psutil; auth-first;
  rate-limited) and an optional periodic `{type:"metrics"}` WS broadcast. The UI
  must work (degrade) if this endpoint is absent.
- Gate the HUD behind `NEXT_PUBLIC_HUD_ENABLED` (default on); when false, render
  the existing static header — implement this flag now, not later.

## Phase 4 — Voice + governance

Create:
- `apps/web/lib/voice/pcmStream.ts` — `startPcmStream(wsUrl): {stop}` using
  `getUserMedia` (mono) + an `AudioWorklet`, converting Float32 → Int16 PCM at
  16 kHz, batching frames, with a `ScriptProcessor` fallback. Implement the DSP
  from the Web Audio spec. In a non-secure context, surface a visible
  explanation, not a silent failure.
- `apps/web/lib/types/tasks.ts` — `AgentTask` type: `id, title,
  status:"queued"|"running"|"done"|"failed"|"retrying"|"recovered", steps,
  error?`. (This is your own type modeling a generic task lifecycle.)
- `components/governance/TaskQueuePanel.tsx` — live task list with status chips,
  expandable steps, failure reason. Register it in `PANELS` in `page.tsx` under
  group `governance`, `minRole:"operator"`. Feed from the existing Planner data
  (`/workflows`) or an additive `/agent/queue`; render all six statuses from
  fixture data when none is live.

Modify:
- `components/VoiceTab.tsx` — wire `pcmStream` with live/stopped states that
  mirror the Composer mic button; drive the HUD `state` to `listening`/`speaking`.

## Hard constraints

- Clean-room per the LEGAL GUARDRAIL. No third-party code, values, or provenance
  headers.
- No component library / CSS framework. Every new file ≤ 400 lines (split if larger).
- Strict TypeScript: no `any`/`unknown` leakage; WS messages narrow through the union.
- Auth state stays in the existing `lib/auth.tsx` flow — never localStorage/
  sessionStorage for tokens (localStorage is allowed for the accent-hue UI pref only).
- Every icon-only button has an `aria-label`. Preserve existing role gating.

## Acceptance criteria

1. `pnpm build` in `apps/web` passes with zero new type errors.
2. Login → control center shows: header status pill, left `SystemRail`, `HudCanvas`
   animating in `idle`, chat feed + composer.
3. Sending a message renders a user bubble immediately and an assistant bubble on
   reply (streaming preserved); system notices render centered.
4. Attaching a file shows a `FileCard` with a live progress bar → ✓ and a working
   download link (or ERR on failure — verify by interrupting the upload).
5. Mic button starts the PCM stream (button pulses red, HUD → `listening`), stop
   restores idle; non-secure context shows an explanation, not a silent failure.
6. WS disconnect shows a system notice and auto-reconnects with backoff.
7. `TaskQueuePanel` appears in the governance rail for operator role only; renders
   all six statuses from fixture data.
8. Accent hue change re-themes HUD, bars, and bubbles live without reload; status
   colors do not shift.
9. No panel is reachable that the current role can't access (existing gating intact).
10. Lighthouse accessibility on the control center ≥ 90.

## Tests required

- Unit (Vitest): `theme.test.ts` (hue-shift preserves status colors, idempotent);
  `ws.test.ts` (union narrowing; malformed JSON doesn't throw); `upload.test.ts`
  (progress sequencing, error path); `tasks.test.ts` (status→render map complete);
  `pcmStream.test.ts` (Float32→Int16 clipping + resample length — pure function).
- Component (Vitest + Testing Library): ChatSurface renders all three bubble types
  and auto-scrolls; Composer Enter-sends / Shift+Enter-doesn't; FileCard three
  states; TaskQueuePanel six statuses; MetricBar threshold colors at 69/71/91.
- E2E (Playwright, extend existing config): login → send → reply; upload → ✓ card;
  role gating (user cannot open TaskQueuePanel); WS reconnect notice.
- Coverage on new files ≥ 80%.

## Rollback

1. All work is on `feat/jarvis-control-center`; nothing merges until acceptance
   passes — rollback = do not merge.
2. If merged and broken: `git revert -m 1 <merge-commit>` (UI-only; no schema
   changes, no migrations).
3. Additive endpoints (`/system/metrics`, `/uploads`) are removed by the revert;
   nothing outside this branch calls them.
4. HUD perf/GPU issues: set `NEXT_PUBLIC_HUD_ENABLED=false` to fall back to the
   static header.
5. Uploaded files under the API uploads dir are transient transfer artifacts —
   safe to delete wholesale.
