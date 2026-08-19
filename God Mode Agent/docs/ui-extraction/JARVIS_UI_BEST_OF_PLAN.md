# JARVIS UI Best-Of Plan

**Target repo:** `C:\dev\JARVIS_2.0\God Mode Agent` — web UI at `apps/web` (Next.js 14.2.35, React 18, no component library, role-gated panel rail already implemented in `app/page.tsx`).

Provenance for every source path below:
- `Mark-L` = FatihMakes/Mark-L @ `b4d6ae9bc7c2c4a60c19361efb1c6c558d129962`
- `Mark-XXXIX-OR` = FatihMakes/Mark-XXXIX-OR @ `eac6378a2411caaca6b382ecc5c04b3c492b5851`
- `jarvis-ai-assistant` = AnubhavChaturvedi-GitHub/jarvis-ai-assistant @ `28d64ec0dad252f7c5aaad468f2ee820a684ace5`

## Recommended target IA

The God Mode web app already has the right bones: a role-gated two-group panel rail (`assistant` / `governance`) with ChatTab as the persistent center. The extraction upgrades it from "tabbed admin panel" to "JARVIS control center":

```
┌──────────────────────────────────────────────────────────────┐
│ Header: JARVIS wordmark · status pill (Active/Sleeping) ·     │
│ enc badge · StatusStrip (existing)                            │
├─────────┬──────────────────────────────────┬─────────────────┤
│ LEFT    │ CENTER — Unified control center  │ RIGHT           │
│ RAIL    │  · HudCanvas (arc reactor,       │ Governance rail │
│ System  │    state: idle/listening/        │ (existing       │
│ metrics │    processing/speaking)          │  operator       │
│ (CPU/   │  · ChatTab feed w/ msg bubbles,  │  panels:        │
│  RAM/   │    file cards, sys notices       │  Approvals,     │
│  GPU/   │  · Composer: input · attach ·    │  Policy, Audit, │
│  net    │    mic (PCM live) · wake · send  │  Compliance,    │
│  bars)  │  · Assistant panels overlay:     │  Evals, Health) │
│ + panel │    Voice/Vision/Memory/Planner/  │ + TaskQueue     │
│  nav    │    Agents/Knowledge              │   panel (new)   │
├─────────┴──────────────────────────────────┴─────────────────┤
│ Integrations hub: IntegrationsPanel (existing) + action       │
│ catalog grid modeled on Mark-L actions registry               │
└──────────────────────────────────────────────────────────────┘
```

- **Unified control center:** keep `app/page.tsx` panel model; make ChatTab + HudCanvas the always-visible center instead of a blank default.
- **Assistant panels:** existing tabs, restyled with the extracted token system.
- **Governance rail:** existing operator-gated group + new TaskQueuePanel visualizing the Mark-XXXIX-OR agent state machine.
- **Integrations hub:** existing IntegrationsPanel becomes an action-catalog grid (name, trigger phrase, status, "run" affordance) modeled on Mark-L's `actions/` registry surface.

## Mapping table

| # | Source path | Target path | Action | Dependency notes |
|---|-------------|-------------|--------|------------------|
| 1 | Mark-L `ui.py` class `C` (l.62–96) + `app.html` `:root` vars (l.10–20) | `apps/web/app/globals.css` (CSS custom props) + `apps/web/lib/theme.ts` | adapt | None — pure CSS vars. Merge the two palettes: navy/cyan HUD tokens from `C`, surface/radius rhythm from app.html. Cross-check `~/.claude/skills/jarvis-design-system` before finalizing values. |
| 2 | Mark-L `app.html` header/pill/feed/msg/footer CSS+markup (l.27–153) | `apps/web/components/chat/ChatSurface.tsx` (+ restyle existing `ChatTab.tsx`) | adapt | None — translate CSS to Tailwind classes; keep `.msg-j/.msg-u/.msg-sys` semantics. |
| 3 | Mark-L `app.html` file-card + upload JS (l.256–372) | `apps/web/components/chat/FileCard.tsx` + `apps/web/lib/upload.ts` | adapt | Needs `/api/upload` + `/uploads/{name}` on jarvis_api; XHR progress → fetch + ReadableStream or keep XHR. |
| 4 | Mark-L `app.html` PCM16 voice section (l.408–556: `_f32toPcm16`, AudioWorklet, `/ws/phone-audio`) | `apps/web/lib/voice/pcmStream.ts` + wire into `components/VoiceTab.tsx` | adapt | Needs `/ws/phone-audio` WS endpoint on jarvis_api; AudioWorklet requires secure context (https/localhost). |
| 5 | Mark-L `dashboard/server.py` WS protocol (msg types `log/status/wake/sys/file_received`; l.580–760) | `apps/web/lib/ws.ts` (typed message union) + jarvis_api WS handler | adapt | Protocol spec only — reimplement server side in existing FastAPI app (`apps/api/jarvis_api`). |
| 6 | Mark-L `ui.py` `HudCanvas` (l.340–598) | `apps/web/components/hud/HudCanvas.tsx` (`<canvas>` + rAF) | rewrite | QPainter → Canvas2D. States: idle/listening/processing/speaking (state vocabulary from jarvis-ai-assistant `ui.py` output-pulse pattern). |
| 7 | Mark-L `ui.py` `MetricBar` (l.599–652) + `_SysMetrics` (l.217–339) | `apps/web/components/hud/MetricBar.tsx` + `SystemRail.tsx`; metrics from new `GET /api/system/metrics` | rewrite | psutil lives server-side in jarvis_api; poll or push over existing WS as `{type:"metrics"}`. |
| 8 | Mark-L `ui.py` `LogWidget` (l.653–763) | restyle `apps/web/components/ToolsTab.tsx` log feed | adapt | Color-coding rules (speaker → color) carry over as a map. |
| 9 | Mark-L `ui.py` `FileDropZone`/`_DropCanvas` (l.774–955) | `apps/web/components/chat/DropZone.tsx` | rewrite | HTML5 drag-drop replaces Qt events; reuse FileCard (#3) for progress. |
| 10 | Mark-L `ui.py` `HueWheel`+`CustomizeOverlay`+`apply_ui_accent` (l.97–168, 1152–1414) | `apps/web/components/settings/AccentPicker.tsx` + `lib/theme.ts` hue-shift fn | rewrite | Hue-rotation of the HSL token set; persists to localStorage or SettingsTab backend (UI prefs only — never auth state). |
| 11 | Mark-L `dashboard/static/login.html` | restyle `apps/web/components/LoginPanel.tsx` | adapt | Visual style only; keep God Mode's existing auth flow — do NOT copy sessionStorage token scheme or client-side AES salt. |
| 12 | Mark-XXXIX-OR `agent/task_queue.py` + `agent/planner.py` state machine | `apps/web/lib/types/tasks.ts` + `apps/web/components/governance/TaskQueuePanel.tsx` | rewrite | Pure TS types + panel; feed from existing PlannerTab data source or new `/api/agent/queue`. |
| 13 | Mark-XXXIX-OR `agent/error_handler.py` retry states | fold into TaskQueuePanel status chips (`retrying`, `recovered`, `failed`) | rewrite | None. |
| 14 | Mark-L `ui.py` `_CameraPreview` (l.956–1022) + camera-swap container in `MainWindow` | `apps/web/components/VisionTab.tsx` live-feed mode (HUD ↔ camera swap) | adapt | Needs frame source (existing Vision backend); pattern = center panel swaps HUD for feed with header + close. |
| 15 | jarvis-ai-assistant `ui.py` (output-activity pulse) + `NetHyTechSTT/listen.py` states | animation-state contract inside `HudCanvas.tsx` (#6) | rewrite | Reference only; no code ported. |

## First 10 files to port (ordered)

1. `apps/web/app/globals.css` — add the merged JARVIS token set (mapping #1). Everything downstream consumes these vars.
2. `apps/web/lib/theme.ts` — token access + hue-shift accent function (mapping #1, #10 foundation).
3. `apps/web/lib/ws.ts` — typed WS message union (`log|status|wake|sys|file_received|metrics`) + reconnecting socket hook (mapping #5).
4. `apps/web/components/chat/ChatSurface.tsx` — message feed with `msg-j/msg-u/msg-sys` bubbles (mapping #2), replacing ChatTab's current feed internals.
5. `apps/web/components/chat/Composer.tsx` — input + attach + mic + wake + send row (mapping #2 footer).
6. `apps/web/components/chat/FileCard.tsx` + `apps/web/lib/upload.ts` — upload progress cards (mapping #3).
7. `apps/web/components/hud/HudCanvas.tsx` — canvas arc-reactor with 4-state animation contract (mapping #6, #15).
8. `apps/web/components/hud/MetricBar.tsx` + `apps/web/components/hud/SystemRail.tsx` — left system rail (mapping #7).
9. `apps/web/lib/voice/pcmStream.ts` — AudioWorklet PCM16 capture → WS (mapping #4), wired into VoiceTab.
10. `apps/web/components/governance/TaskQueuePanel.tsx` + `apps/web/lib/types/tasks.ts` — governance queue visualization (mapping #12–13).

After these ten: DropZone (#9), AccentPicker (#10), LoginPanel restyle (#11), VisionTab camera swap (#14), then sweep remaining tabs onto the new token system.
