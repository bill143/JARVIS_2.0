# OpenHuman advisor + Project Discussion (Phase 5)

Integrates [OpenHuman](https://github.com/tinyhumansai/openhuman) as a **read-only advisor under
JARVIS**, and adds **Project Discussion** mode: JARVIS *chairs* a bounded discussion, OpenHuman
*advises*, and JARVIS synthesizes an outcome. Same manifest + risk-tier + permission-gate scaffold
as the GitHub integration; whitelist-clean (JDK `HttpClient` + Jackson, no SDK).

## Architecture — arm's-length, GPL-safe
OpenHuman is a separate **GPL-3.0** process. JARVIS only ever speaks **HTTP** to it (never links its
code), which keeps JARVIS clear of GPL copyleft. Run OpenHuman's core headless and point JARVIS at
it:

```
openhuman-core serve          # exposes GET /health, GET /schema, POST /rpc (bearer auth)
```

## Auth setup (referenced by name only — never logged)
Configure via environment variables; the plugin stays **dormant** until both are set:

```
setx JARVIS_OPENHUMAN_URL   "http://127.0.0.1:8765"     # the core's base URL
setx OPENHUMAN_CORE_TOKEN   "<the core's bearer token>"  # from {workspace}/core.token
```

`JARVIS_OPENHUMAN_TOKEN` is accepted as an alternative to `OPENHUMAN_CORE_TOKEN`. When unset, the
OpenHuman tools return a graceful "not configured" message and Discussion reports the advisor
offline — nothing crashes.

## Tools (all READ_ONLY — OpenHuman is consulted, never asked to act)
| Tool | Purpose |
|---|---|
| `openhuman_status` | Is the advisor connected/healthy (`GET /health`) |
| `openhuman_schema` | Fetch the core's capability schema (`GET /schema`) |
| `openhuman_memory_search` | Search OpenHuman's Memory Tree by meaning |
| `openhuman_consult` | Ask the advisor a question (its memory + research) |

There are deliberately **no** write, messaging, or payment tools — OpenHuman's outbound messaging
and x402 payments stay off.

## Project Discussion mode
`POST /discussion/run {topic}` runs a **bounded** chair↔advisor loop:
- **Chair = JARVIS** (via the governed `api.chat`) decides each next question and writes the final
  outcome.
- **Advisor = OpenHuman** (`openhuman_consult`) answers from its memory/research.
- Hard cap: `DiscussionRunner.MAX_ROUNDS` (two models can't talk forever).
- Every advisor turn is audited as an `EXTERNAL_API` consult; the transcript persists to
  `~/.jarvis/discussions`.
- An advisor failure is recorded as an explicit error round and ends the discussion **not
  converged** — never silently treated as "nothing to add".
- **Discussion ≠ action.** The outcome is a decision/plan/summary; anything it concludes JARVIS
  should *do* is a separate, permission-gated step.

UI: the **🗣 Discussion** page (Command Center) — enter a topic, watch rounds render, see the
synthesized outcome, with a live ADVISOR ONLINE/OFFLINE badge.

## Known tuning point
OpenHuman publishes the transport (HTTP RPC + bearer) and utility endpoints (`/health`, `/schema`,
`/events`) but not the exact `/rpc` **method names** for memory-search / consult. Defaults are
`memory.search` and `agent.chat` (see `OpenHumanClient`); confirm them against a live core via
`openhuman_schema` (`GET /schema`) and override in `OpenHumanClient` if they differ. Everything else
is confirmed and tested.

## Not built (deliberately deferred — "Tier 2")
Two-way dialogue over OpenHuman's Signal-protocol **agent bridge** (and anything touching **x402
payments**) is out of scope for this pass. Tier 1 here is read-only consult only.

## Tests
- `OpenHumanClientTest` (integrations): health, JSON-RPC consult/memory-search, configurable method
  names, error surfacing, dormant-without-config.
- `DiscussionRunnerTest` (discussion): convergence, hard round budget, advisor-failure = explicit
  error round, chair sees transcript.
- `WebServerTest`: `/discussion/run` drives a bounded loop; `503` when unwired; page is feature-pinned.
