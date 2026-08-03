# ECHO — LiteLLM Proxy

LiteLLM-based multi-provider routing proxy for the JARVIS AI stack at O'Neill Contractors. Part of the NEXUS-EST construction AI platform.

## Status

**Phase 1 — model alias audit complete.** Config verified against provider documentation as of 2026-04-18. Deployment to Railway planned for Phase 8.

## What ECHO Does

ECHO routes incoming chat completion requests to the optimal provider based on the requested alias (tier), with automatic fallback if the primary provider fails. Clients never reference underlying model IDs — they request a tier (e.g., `compliance`, `longdoc`, `bulk`) and ECHO handles routing, fallback, cost tracking, and rate limiting.

### Routing Tiers

| Tier | Alias | Primary Model | Purpose |
|------|-------|---------------|---------|
| 1 | `compliance` | Claude Opus 4.7 | Highest-reasoning, bet-the-company decisions |
| 2 | `longdoc` | GPT-5.4 (1.05M ctx) | Document ingestion, spec review |
| 3 | `strategy` | Claude Sonnet 4.6 | General reasoning, daily driver |
| 4 | `bulk` | DeepSeek V3.2 | High-volume background tasks |
| 5 | `reasoning` | DeepSeek R1 | Explicit step-by-step analysis |
| 6 | `realtime` | Grok 4.1 Fast (2M ctx) | Fast, huge context, cheap |

See `litellm_config.yaml` for full fallback chains and pricing.

## Local Development

Requires Python 3.11+, Docker, and API keys for each provider.

Setup:

    cp .env.example .env      # then populate with real keys
    docker compose up -d      # coming in Phase 2

## Consumers

- `nexus-est-app` (Vercel) — primary consumer via `POST /api/echo/chat`
- `JARVIS_2.0` (local workstation) — voice-enabled AI assistant, wired to ECHO in Phase 3

## Project Phases

- **Phase 0** — Bootstrap (complete)
- **Phase 1** — Model alias audit + config (complete)
- **Phase 2** — Hardening: Dockerfile, observability, /health, secret scanning
- **Phase 3** — Wire JARVIS to ECHO
- **Phase 4** — JARVIS voice I/O
- **Phase 5** — Local end-to-end test
- **Phase 6** — Construction MCP + Doc Management agent
- **Phase 7-8** — Production deploy to Railway

## License

Proprietary — O'Neill Contractors, Inc.
