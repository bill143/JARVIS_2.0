# CLAUDE IMPLEMENTATION PROMPT — JARVIS Control-Center UI Port

Copy everything below the line into a fresh Claude Code session opened at `C:\dev\JARVIS_2.0\God Mode Agent`.

---

You are implementing the JARVIS control-center UI upgrade in this repo (`apps/web`, Next.js 14.2.35 + React 18). The design inputs are:

- `docs/ui-extraction/UI_EXTRACT_MANIFEST.json` — source repos, commit SHAs, and exact files extracted
- `docs/ui-extraction/JARVIS_UI_BEST_OF_PLAN.md` — mapping table and port order (follow its "First 10 files to port" exactly)
- Source clones (re-clone if absent): FatihMakes/Mark-L @ `b4d6ae9bc7c2c4a60c19361efb1c6c558d129962`, FatihMakes/Mark-XXXIX-OR @ `eac6378a2411caaca6b382ecc5c04b3c492b5851`, AnubhavChaturvedi-GitHub/jarvis-ai-assistant @ `28d64ec0dad252f7c5aaad468f2ee820a684ace5`
- The `jarvis-design-system` skill — load it before styling anything; where it conflicts with extracted source values, the skill wins.

Work on a new branch `feat/jarvis-control-center` off the current default branch. Do not touch `apps/api` except where explicitly listed. Do not add any component library or CSS framework.

## Phase 1 — Foundation (no visual regression allowed)

**Create:**
- `apps/web/lib/theme.ts` — export the token map (from Mark-L `ui.py` class `C` l.62–96 merged with `app.html` `:root` l.10–20) and `applyAccentHue(hex: string)` that hue-rotates only the hue-linked tokens (see `_HUE_LINKED` in Mark-L `ui.py` l.88–95; status colors green/red/amber stay fixed).
- `apps/web/lib/ws.ts` — `JarvisSocketMessage` discriminated union (`{type:"log",speaker,text} | {type:"status",state:"active"|"sleeping"} | {type:"wake"} | {type:"sys",text} | {type:"file_received",name,size} | {type:"metrics",cpu,ram,gpu,netUp,netDown}`) and a `useJarvisSocket(url)` hook with exponential-backoff reconnect.

**Modify:**
- `apps/web/app/globals.css` — add the CSS custom properties for every token in `theme.ts`. Do not remove existing rules.

## Phase 2 — Chat surface

**Create:**
- `apps/web/components/chat/ChatSurface.tsx` — message feed: `msg-j` (assistant, accent-tinted, bottom-left square), `msg-u` (user, surface-tinted, bottom-right square), `msg-sys` (centered muted system line), auto-scroll, pop-in animation. Port markup/CSS semantics from Mark-L `app.html` l.54–86 & l.233–254.
- `apps/web/components/chat/Composer.tsx` — input row: text input (Enter sends, Shift+Enter newline), attach button (hidden file input, multiple), mic button (recording state = red pulse), WAKE button (visible only when status=sleeping), SEND button. Port from `app.html` l.146–153 & l.374–397.
- `apps/web/components/chat/FileCard.tsx` — upload card with icon-by-extension, name, size, progress bar, ✓/ERR status, download-back link. Port from `app.html` l.256–372.
- `apps/web/lib/upload.ts` — `uploadFile(file, onProgress)` using XHR for progress events, POSTing to the existing API upload route (add `POST /api/upload` + `GET /uploads/{filename}` to `apps/api/jarvis_api` if absent, modeled on Mark-L `dashboard/server.py` l.643–722 but using God Mode's existing auth dependency, not Mark-L's token scheme).

**Modify:**
- `apps/web/components/ChatTab.tsx` — compose ChatSurface + Composer + FileCard; preserve its existing data flow through `lib/api.ts`.

## Phase 3 — HUD and system rail

**Create:**
- `apps/web/components/hud/HudCanvas.tsx` — `<canvas>` + requestAnimationFrame arc-reactor: concentric rotating rings, particle drift, center glow. Props: `state: "idle" | "listening" | "processing" | "speaking"` (speaking pulses ring radius — the jarvis-ai-assistant output-pulse pattern). Reference geometry: Mark-L `ui.py` `HudCanvas` l.340–598. Must pause rAF when tab hidden (`visibilitychange`).
- `apps/web/components/hud/MetricBar.tsx` — labeled thin bar with value text, color shifts to amber >70%, red >90% (Mark-L `ui.py` l.599–652).
- `apps/web/components/hud/SystemRail.tsx` — stacks MetricBars for CPU/RAM/GPU/NET fed by `{type:"metrics"}` WS messages.

**Modify:**
- `apps/web/app/page.tsx` — center column renders HudCanvas above ChatTab (HUD collapses to a compact strip when an assistant panel is open); mount SystemRail in the left rail above the panel nav buttons.
- `apps/api/jarvis_api` — add `GET /api/system/metrics` (psutil) and a periodic `{type:"metrics"}` WS broadcast. Auth-first per house rules; rate-limit the polling route.

## Phase 4 — Voice + governance

**Create:**
- `apps/web/lib/voice/pcmStream.ts` — `startPcmStream(wsUrl): {stop}` — getUserMedia mono, AudioWorklet capture, Float32→Int16 resample-to-16kHz (port `_f32toPcm16` and worklet from Mark-L `app.html` l.408–556), 1024-sample batching, ScriptProcessor fallback.
- `apps/web/lib/types/tasks.ts` — `AgentTask` type: `id, title, status: "queued"|"running"|"done"|"failed"|"retrying"|"recovered", steps, error?` (from Mark-XXXIX-OR `agent/task_queue.py` + `error_handler.py` states).
- `apps/web/components/governance/TaskQueuePanel.tsx` — live task list with status chips, expandable steps, failure reason; registers in `PANELS` in `page.tsx` under group `governance`, `minRole: "operator"`.

**Modify:**
- `apps/web/components/VoiceTab.tsx` — wire pcmStream with live/stopped states mirroring the mic button in Composer.

## Hard constraints

- Never copy: Mark-L `config/certs/*` (committed private key), `crypto-js.min.js` (use WebCrypto if encryption is required), Mark-L's sessionStorage token auth, any `actions/`/`core/`/`memory/` backend code, any binary/GIF from jarvis-ai-assistant.
- Every new file ≤400 lines; split if larger.
- No `any`/`unknown` leakage in new TS; WS messages must narrow through the discriminated union.
- Auth state stays in the existing God Mode auth flow (`lib/auth.tsx`) — never localStorage/sessionStorage.
- Add a provenance header comment to every ported file: `// Ported from <repo>@<sha> <path> (adapted)`.

## Acceptance criteria

1. `pnpm build` in `apps/web` passes with zero new type errors.
2. Login → control center shows: header status pill, left SystemRail with live metric bars, HudCanvas animating in `idle`, chat feed + composer.
3. Sending a chat message renders a `msg-u` bubble immediately and a `msg-j` reply bubble when the API responds; system notices render centered.
4. Attaching a file shows a FileCard with a live progress bar ending in ✓ and a working download link (or ERR state on failure — kill the API mid-upload to verify).
5. Mic button: starts PCM stream (button pulses red, HUD switches to `listening`), stop restores idle. In a non-secure context it degrades to a visible explanatory message, not a silent failure.
6. WS disconnect shows a system notice and reconnects automatically with backoff (verify by restarting the API).
7. TaskQueuePanel appears in the governance rail for operator role only; renders all six task statuses correctly from fixture data.
8. Accent hue change re-themes HUD, bars, and bubbles live without reload; status colors (green/red/amber) do not shift.
9. No panel is reachable that the current role can't access (existing role gating unbroken).
10. Lighthouse accessibility on the control center ≥ 90; all icon-only buttons have `aria-label`.

## Tests required

- **Unit (Vitest):** `theme.test.ts` (hue-shift preserves status colors, is idempotent at same hue); `ws.test.ts` (message parsing narrows every union member, malformed JSON doesn't throw); `upload.test.ts` (progress callback sequencing, error path); `tasks.test.ts` (status transitions render map completeness); `pcmStream.test.ts` (Float32→Int16 conversion: clipping bounds, resample length math — pure function test).
- **Component (Vitest + Testing Library):** ChatSurface renders all three bubble types and auto-scrolls; Composer Enter-sends/Shift-Enter-doesn't, WAKE visibility toggles on status; FileCard all three states; TaskQueuePanel all six statuses; MetricBar threshold colors at 69/71/91.
- **E2E (Playwright, extend existing config):** login → send message → see reply; upload file → ✓ card; role gating (user cannot open TaskQueuePanel); WS reconnect notice.
- Coverage on new files ≥ 80%.

## Rollback steps

1. All work is on `feat/jarvis-control-center`; nothing merges until acceptance criteria pass — rollback = do not merge.
2. If merged and broken: `git revert -m 1 <merge-commit>` (UI-only phases are revert-safe; no schema changes, no data migrations).
3. API additions (`/api/system/metrics`, upload routes, metrics broadcast) are additive endpoints — revert removes them; no client outside this branch calls them.
4. If only the HUD misbehaves (perf/GPU): feature-flag it — `NEXT_PUBLIC_HUD_ENABLED=false` renders the previous static header; implement this flag as part of Phase 3, not as an afterthought.
5. Uploaded files live under the API's uploads dir; safe to delete wholesale — they are transient transfer artifacts, not records.
