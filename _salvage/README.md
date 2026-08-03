# _salvage — extracted 2026-07-25

Consolidated from 5 folders slated for deletion. 93 files, 804 KB.
Nothing here is wired into the build yet. Review, then place.

| Folder | Source | Contents |
|---|---|---|
| 01_echo_spec | NEXUS ECHO AI | ECHO_SPEC_V3.md (647 lines, 10-module architecture) + ECHO_CLAUDE.md |
| 02_echo_core | NEXUS ECHO AI | 20 of 25 TypeScript files incl. all 5 compliance validators, vault, knowledge-graph, composition root |
| 03_precon_govtribe | NEXUS_Relay | 21 Python files: GovTribe adapter + MCP client, 8-stage pipeline, bid leveling, prequal, RFQ, sync/heartbeat tasks, 66 tests |
| 04_ai_registry | NEXUS_Relay | 15 files: agent org chart, call signs, 3 persona cards, guardrail/tools/handoff templates |
| 05_eval_harness | NEXUS_Relay | pgvector memory schema (RLS-complete), 30-case eval grid, scoring harness |
| 06_llm_gateway | JARVIS & ECHOS NEW REPO | 12 files: 6-tier / 16-model litellm_config.yaml, cost logger, health probe, Dockerfile, CI, Railway config |
| 07_design_tokens | NEXUS JARVIS | tailwind.config.js, index.css, clean rebrand merge base, provider-proxy vite config, prior QC report |
| 08_provisioning | JARVIS & ECHOS NEW REPO | bootstrap.ps1 (851 lines) + report + error log |

## STILL CLOUD-ONLY — 12 files could not be copied

OneDrive did not hydrate these even after "Always keep on this device" was
applied at the parent folder. Right-click each SUBFOLDER below directly and
re-apply, then re-run the copy.

NEXUS ECHO AI\echo\agents\operator\   (BashAgent, ComputerAgent, EditAgent, OrchestratorAgent .ts)
NEXUS ECHO AI\echo\services\vision\   (echo-vision.ts)
NEXUS JARVIS\src\services\            (echoSystemPrompt.ts - 9 KB, top salvage item)
NEXUS_Relay\ON_NEXUS_ERP\backend\app\modules\precon\   (__init__, models, schemas, repository, router, service .py - ~50 KB)

Do NOT delete any source folder until these 12 are recovered.
