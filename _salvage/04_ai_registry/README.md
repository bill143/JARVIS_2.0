# NEXUS_AI_REGISTRY

Source of truth for all NEXUS AI agents — design-time.
Runtime state lives in Supabase. This folder is the spec.

## Folder Map
- **00_REGISTRY**     Master agent list, call signs, org chart
- **01_PERSONAS**     One Persona Card per agent, grouped by module
- **02_PROMPTS**      Versioned system prompts per agent
- **03_GUARDRAILS**   Per-agent hard rules (YAML)
- **04_TOOLS**        Per-agent tool/API authorizations (YAML)
- **05_TESTS**        Eval suites per agent
- **06_LOGS**         Local audit cache
- **07_SCORECARDS**   Weekly performance reports
- **08_HANDOFFS**     Agent-to-agent contracts

## Sync Direction
Folder > Git > CI > Supabase

## Owner
Bill Asmar — O'Neill Contractors, Inc.
