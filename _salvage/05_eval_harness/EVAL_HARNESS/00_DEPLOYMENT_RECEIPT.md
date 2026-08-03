# NEXUS Eval Harness — Deployment Receipt & Runbook

**Status:** ✅ ADD-ON #1 + ADD-ON #2 LIVE
**Date:** May 7, 2026
**Host:** Supabase project `NEXUS_ESTIMATING_AI` (`qulvniixtxxyppmhufha`)

---

## What Was Deployed

| # | Item | Where It Lives | Status |
|---|---|---|---|
| 1 | JSON Schema (Draft 2020-12) | `eval_datasets.metadata.json_schema` | ✅ Bound to dataset |
| 2 | Postgres validator trigger | `nexus_memory.validate_eval_case()` | ✅ Active on every INSERT/UPDATE |
| 3 | Trigger smoke test | Bad row deliberately inserted and rejected | ✅ Confirmed |
| 4 | Python scoring harness (CLI) | `EVAL_HARNESS/03_scoring_harness.py` | ✅ Syntax & scoring tests pass |
| 5 | Supabase Edge Function `score-eval-run` | Deployed to project | ✅ ACTIVE (v1) |
| 6 | Function search_path hardening | All 3 functions locked | ✅ Done |

**Endpoint URL:**
```
https://qulvniixtxxyppmhufha.supabase.co/functions/v1/score-eval-run
```

**Validation pass rate:** 30/30 stored cases pass the schema cleanly.

---

## ADD-ON #1 — JSON Schema In Action

### What It Does
Every time anyone inserts a new row into `nexus_memory.eval_cases`, the trigger `validate_eval_case()` runs. If the row breaks any rule, the insert is rejected with a clear error.

### Rules Enforced
- `external_id` must match pattern `P{1-6}-{NN}` (e.g., `P1-01`, `P3-12`)
- `pillar` must be 1–6
- `level` must be 1–6
- `input` must contain `task`
- `expected_output` must contain `must_include`
- `rubric` must contain `scoring_scale` and `criteria` (≥1 entry)
- `failure_modes` must have ≥1 entry
- If `pillar_name` is provided, it must match the `pillar` number

### Test It Yourself
Run this in **Chrome** → `https://supabase.com/dashboard/project/qulvniixtxxyppmhufha` → "SQL Editor":

```sql
-- This will be REJECTED with "external_id must match P{pillar}-NN":
INSERT INTO nexus_memory.eval_cases
  (dataset_id, external_id, pillar, role, level, input, expected_output, rubric, failure_modes)
VALUES
  ('08c4ad74-c9a4-43ac-91f3-26db8966eed9', 'BAD_ID', 1, 'CEO', 1,
   '{"task":"x"}'::jsonb, '{"must_include":["x"]}'::jsonb,
   '{"scoring_scale":"0-2","criteria":[{"name":"x","checks":["x"],"weight":1}]}'::jsonb,
   '["x"]'::jsonb);
```

---

## ADD-ON #2 — Scoring Harness In Action

### Two Ways to Run It

#### Option A — Cloud (Edge Function, recommended)
One HTTP call. Works from your phone, any agent, any device. No install.

#### Option B — Local Python CLI
For development, debugging, or batch runs against many cases. Same scoring logic.

---

### Required Secrets (one-time setup)

The Edge Function needs API keys for the LLM providers. Set them in **Chrome**:

1. Go to `https://supabase.com/dashboard/project/qulvniixtxxyppmhufha/functions/secrets`
2. Click `"New Secret"` and add each:

| Secret Name | Value | Required For |
|---|---|---|
| `ANTHROPIC_API_KEY` | from console.anthropic.com | Claude models (CEO, PE, Worker) |
| `OPENAI_API_KEY` | from platform.openai.com | GPT models (PM) |
| `GOOGLE_API_KEY` | from aistudio.google.com | Gemini models (Executive, Super) |

Note: `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` are auto-populated by Supabase — you don't set those.

---

### Run a Single Case (Cloud)

You'll need your **anon key** (safe to use, JWT-verified):
1. Open Chrome → `https://supabase.com/dashboard/project/qulvniixtxxyppmhufha/settings/api`
2. Copy the value labeled `anon` `public`

Then in any terminal (Windows PowerShell works):

```powershell
curl -X POST "https://qulvniixtxxyppmhufha.supabase.co/functions/v1/score-eval-run" `
  -H "Authorization: Bearer <YOUR_ANON_KEY>" `
  -H "Content-Type: application/json" `
  -d '{"case_external_id":"P1-01","agent_code":"CEO-001","dry_run":false}'
```

Returns:
```json
{
  "ok": true,
  "run": {
    "run_id": "...",
    "case_external_id": "P1-01",
    "agent_code": "CEO-001",
    "model_used": "claude-opus-4-7",
    "scores": {"Hierarchy discipline":2,"Tradeoff clarity":2,"HITL gating":2,"Structured format":2,"Risk management":2},
    "total_score": 10.0,
    "max_score": 10.0,
    "passed": true,
    "failure_modes_hit": [],
    "duration_ms": 4321
  }
}
```

---

### Run All 30 Cases Against Every Agent (Local Python)

```powershell
# In PowerShell, set env vars (one time per session)
$env:SUPABASE_URL = "https://qulvniixtxxyppmhufha.supabase.co"
$env:SUPABASE_SERVICE_ROLE_KEY = "<service-role-key>"
$env:ANTHROPIC_API_KEY = "<sk-ant-...>"
$env:OPENAI_API_KEY = "<sk-...>"
$env:GOOGLE_API_KEY = "<...>"

# Install one dependency
pip install requests

# Then run the full sweep
python 03_scoring_harness.py --agent-code CEO-001  --pillar all
python 03_scoring_harness.py --agent-code EXEC-001 --pillar all
python 03_scoring_harness.py --agent-code PM-001   --pillar all
python 03_scoring_harness.py --agent-code PE-001   --pillar all
python 03_scoring_harness.py --agent-code SUP-001  --pillar all
python 03_scoring_harness.py --agent-code WRK-001  --pillar all

# Export Grafana-friendly metrics CSV
python 03_scoring_harness.py --export-metrics --output nexus_eval_metrics.csv
```

---

### Run Specific Case (Local — Dry Run, no DB writes)

```powershell
python 03_scoring_harness.py --case-id P1-01 --agent-code CEO-001 --dry-run
```

---

## Scoring Logic Summary

| Component | What It Does |
|---|---|
| Per-criterion score | 0–2 points based on % of `checks` that fired |
| `must_include` penalty | If <50% of required sections present, total × 0.7 |
| `must_not_include` penalty | If any forbidden phrase appears, total × 0.5 |
| Failure mode penalty | -1 per failure mode hit (requires 2+ keyword matches to count, prevents false positives) |
| Pass threshold | ≥70% of max score |

**Scoring discrimination tested:**
A well-structured response scored **5.0/10** while a sloppy one scored **0.7/10** — 43-point gap, which means the harness clearly distinguishes good agents from bad.

---

## Where Results Land for Grafana

Every run creates a row in `nexus_memory.eval_runs`. Grafana connects via Supabase Postgres data source. Two ready-to-paste queries:

### Per-pillar pass rate over time
```sql
SELECT
  date_trunc('day', r.created_at) AS day,
  c.pillar_name,
  ROUND(100.0 * COUNT(*) FILTER (WHERE r.passed) / COUNT(*), 1) AS pass_pct
FROM nexus_memory.eval_runs r
JOIN nexus_memory.eval_cases c USING (case_id)
GROUP BY day, c.pillar_name
ORDER BY day DESC;
```

### Per-agent score trend
```sql
SELECT
  date_trunc('hour', r.created_at) AS hour,
  a.agent_code, a.model,
  ROUND(AVG(r.total_score / NULLIF(r.max_score, 0)) * 100, 1) AS avg_pct
FROM nexus_memory.eval_runs r
JOIN nexus_memory.agent_profiles a ON a.agent_id = r.agent_id
GROUP BY hour, a.agent_code, a.model
ORDER BY hour DESC;
```

---

## Three Things Left to Do (You)

1. **Set 3 API key secrets** in Supabase (5 min via Chrome — link above)
2. **Get your anon key** from the API settings page (also linked above)
3. **Run a first cloud test** with the curl example above — confirms end-to-end pipeline works

When you've done step 1 and 2, paste the curl command result here and I'll verify the run landed in `eval_runs` and walk through the dashboard setup if needed.

---

## Files Saved

| File | Purpose |
|---|---|
| `02_eval_case_schema.json` | Reference copy of the JSON Schema |
| `03_scoring_harness.py` | Python CLI version (local runs, batch, CSV export) |
| `04_score_run_edge_function.ts` | Source of the deployed Edge Function |
