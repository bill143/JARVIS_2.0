\# JARVIS DESIGN SYSTEM — LOCKED CONTRACT

Claude Code must not deviate. No new colors, no new fonts, no new layout patterns.



\## LAYOUT LAW

\- ONE primary surface. The reactor orb occupies center, 55vh minimum.

\- Maximum 4 persistent navigation targets. Everything else lives behind Cmd/Ctrl+K palette.

\- NO accordion panel stacks. NO symmetric left/right panel columns.

\- Panels are TRANSIENT: they slide in over the surface on demand, then dismiss.

\- Asymmetric composition. Orb sits at 42% horizontal, not 50%.



\## COLOR

\--void: #05070A          /\* base \*/

\--surface: #0B1016       /\* raised \*/

\--reactor: #4FC3F7       /\* primary accent — orb only \*/

\--reactor-dim: #1A5F7A   /\* orb falloff \*/

\--signal: #FFB454        /\* secondary accent — alerts/state ONLY \*/

\--text: #E8EDF2

\--text-dim: #6B7A8A

\--edge: rgba(79,195,247,0.08)

Rule: --signal appears on max 1 element per screen. --reactor never used on text.



\## TYPOGRAPHY

\- Display/UI: "Space Grotesk" (Google Fonts)

\- Mono/data: "JetBrains Mono"

\- Scale: 11 / 13 / 15 / 20 / 32 / 56

\- Uppercase permitted ONLY at 11px with 0.14em tracking. Never above 13px.



\## MOTION

\- Orb: continuous 4s breathing scale 1.0 → 1.03, ease-in-out.

\- Orb pulses to voice amplitude when speaking. This is the primary feedback channel.

\- Panel entry: 220ms cubic-bezier(0.16, 1, 0.3, 1), translateY(8px) + opacity.

\- NO spinners anywhere. State is communicated by the orb.



\## DENSITY

\- Base spacing unit: 8px. All padding/margin is a multiple.

\- Minimum 32px between distinct content groups.

\- Chat input: borderless, transparent background, bottom-anchored, no SEND button. Enter submits.



\## FORBIDDEN

\- Rounded-full pill buttons in navigation

\- Emoji in UI chrome

\- Collapsed accordion sections

\- Card grids with equal-weight tiles

\- Any gradient other than the orb radial

\- Box-shadow for elevation (use --edge borders)

