# Review: Vision presence-greeting SSE + dashboard v2 redesign

Scope: the uncommitted Java vision changes and the paired `dashboard.html` redesign.
Excluded (not vision/dashboard, left alone per instructions): `configs/openjarvis/config.toml`
(leftover routing-config residue from an unrelated earlier session) and `java/server.log`
(a local runtime log, not source — see Notes).

## Files

| File | Purpose | Status | Notes |
|---|---|---|---|
| `app/src/main/java/com/jarvis/app/VisionEventBroadcaster.java` (new) | SSE fan-out: pushes motion-triggered greetings to every connected dashboard tab at `/vision/events` | ✅ Complete | Bounded per-subscriber queue, non-blocking `publish()`, global debounce independent of per-camera cooldown, ping keep-alive. No TODOs/stubs. |
| `app/src/main/java/com/jarvis/app/AppWiring.java` | Wires `VisionEventBroadcaster` into `VisionServices` | ✅ Complete | 5-line diff, minimal and correct. |
| `app/src/main/java/com/jarvis/app/WebServer.java` | Publishes greeting events; adds `GET /vision/events`; switches the HTTP executor to virtual threads; adds `disk` % to `/telemetry` | ✅ Complete | The fixed→virtual thread pool switch is a real fix, not incidental — a long-lived SSE connection would otherwise starve the old 4-thread pool. Well-justified in comments. |
| `app/src/test/java/com/jarvis/app/VisionEndpointsTest.java` | Updated for the new `VisionServices` constructor arg | ✅ Complete | 14/14 passing. |
| `app/src/main/resources/dashboard.html` | Full v1 (orb/sidebar HUD) → v2 (card-grid) redesign per `JARVIS_DESIGN_SYSTEM.md`, plus the vision SSE client, emergency-mute, TTS-boundary-driven orb pulse, Enter-to-submit fix | ⚠️ Needs one fix | See Bug below. Everything else checked out — see Findings. |

## Dependencies

No new external dependencies. `VisionEventBroadcaster` uses only JDK APIs (`com.sun.net.httpserver`, `java.util.concurrent`). Nothing to add to `pom.xml`.

## Compilation & tests (actually run, not assumed)

```
mvnw -pl app -am compile          → BUILD SUCCESS
mvnw -pl app -am test             → Tests run: 319, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS
```
`VisionEndpointsTest`: 14/14. `WebServerTest`: 58/58. `MotionEventServiceTest`: 13/13.

The bug below is client-side JS — the Java test suite serves the page as bytes and never executes its script, so it can't catch this class of defect. Found by statically diffing every `getElementById()` call against every `id="..."` in the shipped HTML.

## Bug — fixed

**`dashboard.html`, line 1943: `document.getElementById('hdrDot').className = ...` — no element with `id="hdrDot"` exists anywhere in the page.**

This line sits inside the one-time initial `fetch('/status').then(s => {...})` handler that runs on every page load. Calling `.className` on the `null` returned by `getElementById` throws `TypeError: Cannot set properties of null`, which aborts the rest of that callback. Everything *after* line 1943 in the same handler never runs on initial load:
- the welcome message (`add('jarvis', 'Good morning/afternoon/evening, sir...')`) never appears in the chat log
- `setInterval(pollAlerts, 20000)` never starts — alert polling is dead until next full reload
- `S.autoBrief` auto-briefing and `S.voiceOnStart` auto-voice-start never fire
- `modelKv`/`connKv`/`ssAi`/`ssVision`/`ssGoogle`/`sbNotes` don't get their initial values (they self-heal 15s later via `refreshBrainBadge`/polling, but the rest above don't)

This is a real regression introduced by this diff, not pre-existing: `hdrDot` is referenced in exactly two places, both added by this change (confirmed via `git diff`). The second occurrence (`refreshBrainStatus()`, ~line 3232) correctly null-guards it:
```js
const hDot=document.getElementById('hdrDot'); if(hDot) hDot.className='dot '+(s.online?'on':'off');
```
but the first (the initial-load handler) does not. Root cause: the new `.cg-status-pill` header dot (`<span class="dot"></span>` inside `#cgStatusPill`) was never given an `id`, and its actual color already comes from `cgRenderStatus()` toggling `.cg-status-pill.degraded` — a CSS descendant-selector rule already recolors that dot on the new v2 header. So `hdrDot` looks like a leftover reference from an earlier draft rather than a feature gap.

**Applied fix:** guarded the lookup to match the existing sibling pattern (rather than deleting the line):
```js
const hDot = document.getElementById('hdrDot');
if (hDot) hDot.className = 'dot ' + (s.online ? 'on' : 'off');
```
Re-ran `VisionEndpointsTest` after the change (14/14 still pass — this file isn't compiled, so this was a sanity check, not a regression risk).

### Fixes Applied

| File | Issue | Fix | Status |
|---|---|---|---|
| `dashboard.html` (line ~1943) | `getElementById('hdrDot')` throws on every page load (no such element), breaking the welcome message, alert polling, auto-brief, and auto-voice-start | Added `if (hDot)` guard, matching the existing pattern at line ~3232 | ✅ Fixed |
| `dashboard.html` (line ~1032) | Duplicate `id="paletteScrim"` (invalid HTML; harmless since `getElementById` bound to the first occurrence) | Removed the second, dead copy inside the hidden `.cg-legacy` block | ✅ Fixed |

## Minor — informational, not touched

- **Design-contract deviation, but a documented one:** `JARVIS_DESIGN_SYSTEM.md`'s FORBIDDEN section bans "mixing in the old v1 tokens (`--void`/`--reactor`/`--signal` at the old hex values)." The diff keeps them in `:root`, with a comment explaining why: ~20 content-page views (Solicitations, Tasks, Workflows, Agents, Galaxy, Audit, etc.) still consume the v1 tokens and haven't been migrated to the card-grid system yet, and the old orb/status-panel/statusbar markup is kept alive (invisible, via `.cg-legacy`) so existing JS hooks (`ask`/`speak`/`setOrb`/`loadActivity`) keep working. This is explicitly called out as an incremental migration tracked separately, not silent scope-cutting. I'd treat this as acceptable engineering debt rather than a defect — flagging so it's a conscious call, not a surprise later.
- **`java/server.log`** (untracked, 11 lines): a local run's stdout capture, not source. Neither `.gitignore` nor `java/` has a `*.log` rule. Recommend adding one and not committing this file — didn't touch it, your call.
- **`configs/openjarvis/config.toml`**: modified, but the diff (`cost_threshold`/`classifier_hash_dim`/`similarity_k` under `[learning.routing]`) is residue from the earlier routing-module session, unrelated to vision/dashboard. Out of scope here — flagging so it isn't lost track of.

## Recommendation

**Ready to commit.** This is real, tested, well-reasoned work (SSE fan-out design, virtual-thread executor fix, TTS-boundary orb sync, emergency mute), and both findings above are now fixed in `dashboard.html`. No other file was touched. Not yet committed — say the word if you want it committed.
