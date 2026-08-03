---
name: jarvis-design-system
description: Visual design contract for the JARVIS dashboard. Use whenever building, restyling, or laying out any JARVIS UI surface — header, sidebar, dashboard cards, gauges, modals, or the bottom dock. Defines color tokens, type scale, spacing, motion, and forbidden patterns.
---

# JARVIS DESIGN SYSTEM v2 — CARD-GRID CONTRACT

Design contract authored by Bill Asmar, superseding the earlier orb/palette single-surface
contract (2026-07-22). That version is retired — this dashboard is a multi-card command
console, not a one-surface HUD. Treat this as binding for JARVIS UI work.

## LAYOUT LAW

- Persistent chrome: top header bar + left sidebar, both always visible (not palette-only).
- Main content is a CSS grid of cards — unequal weights are fine and expected (a gauge
  cluster next to a wide feed card next to a compact list card).
- Modals are centered overlays with a dimmed/blurred backdrop, not full-page takeovers.
- Bottom dock bar is persistent: status pills on the left, one primary CTA pill centered,
  a secondary action on the right.
- Sidebar nav items carry optional numeric badges (unread/count indicators).

## COLOR

--void: #080C14          /* base background */
--surface: #0D1527       /* raised panel/card background */
--border: #1A2942        /* muted blue-gray card/divider border */
--cyan: #00F2FE          /* primary accent — glows, active states, links */
--cyan-2: #00C6FF        /* secondary cyan — gradients paired with --cyan */
--amber: #FFB300         /* warning / degraded / attention accent */
--green: #22C55E         /* optimal / online / ready */
--red: #EF4444           /* error / offline */
--text: #E8EDF2
--text-dim: #7A8AA0

Rule: --amber marks "degraded/standby/warning" states; --green marks "optimal/ready/online";
never swap them. --cyan is the only accent used for glows, active borders, and primary CTAs.

## TYPOGRAPHY

- Display/UI: "Inter" (Google Fonts), system-ui fallback.
- Mono/data: "JetBrains Mono" — gauge readouts, timestamps, version strings only.
- Scale: 11 / 12 / 13 / 15 / 18 / 24
- Uppercase permitted at 10–11px with 0.08em tracking for section labels (card titles,
  sidebar section headers). Never above 13px.

## MOTION

- Gauges animate their arc on value change: 500ms ease-out.
- Waveform bars: continuous randomized height animation while "listening"/"active", still
  when idle.
- Card/modal entry: 200ms ease-out, translateY(6px) + opacity.
- Live clock ticks every second; no layout shift on tick.

## DENSITY

- Base spacing unit: 8px. All padding/margin is a multiple.
- Card padding: 16px. Card-to-card gap: 16px.
- Card border-radius: 14px. Pill buttons/badges: fully rounded.

## COMPONENT PATTERNS

- Card: var(--surface) background, 1px var(--border), 14px radius, header row with small
  icon + uppercase 11px label, optional trailing action.
- Status row: colored dot (green/amber/red) + label + state text, used inside overview cards.
- Gauge: circular/donut arc in --cyan (or --amber past a warning threshold) over a dim
  track, percentage centered in JetBrains Mono.
- Badge: small pill, --cyan background at low opacity, count in --text.
- Primary CTA (e.g. the bottom-dock "talk" button): fully rounded pill, --cyan glow
  (box-shadow permitted here — glow is the point of this dock button specifically).
- Sidebar nav item: rectangular row, active state = left accent bar in --cyan + subtle
  --cyan background wash. Not a pill.

## FORBIDDEN

- Reusing --amber for anything "good" or --green for anything "bad/degraded"
- More than one primary CTA glow per screen
- Unbounded box-shadow glow on non-CTA elements (cards use border only, not glow)
- Mixing in the old v1 tokens (--void/--reactor/--signal at the old hex values) — fully
  replaced by the tokens above
