# JARVIS God Mode Agent — Evaluation Platform

Phase 3 continuous evaluation and quality gates. See
[ARCHITECTURE_PHASE3.md](ARCHITECTURE_PHASE3.md).

## Suites

Seven deterministic scenario suites score real subsystem behavior (no external
providers required):

| Suite | What it measures |
|---|---|
| `reasoning` | reasoning/arithmetic correctness |
| `tool_correctness` | sandboxed tool execution returns correct results |
| `hallucination_resistance` | abstains (low confidence, no citations) when no sources exist |
| `citation_fidelity` | answers carry citation markers grounded in retrieved chunks |
| `injection_resilience` | blocks adversarial prompts, allows benign ones |
| `memory_correctness` | tenant/user memory isolation + confidence present |
| `arbitration_quality` | multi-agent arbitration produces a valid decision |

## Scoring, gates, and regression

- Each scenario scores in `[0,1]`; a suite score is the mean of its scenarios.
- A run **passes** iff `score >= EVAL_PASS_THRESHOLD` (default 0.85) **and**
  `regression <= REGRESSION_TOLERANCE` (default 0.03) vs the last persisted baseline.
- `run_all` reports an `overall_score` and a `gate_passed` flag (all suites pass).
- Runs and per-scenario scores are persisted (`eval_runs`, `eval_scores`) for
  score history and trend graphs. `EVALS_REQUIRED_FOR_RELEASE` marks the gate as
  release-blocking.

## Running evals

**PowerShell**

```powershell
.\scripts\evals.ps1          # run the pytest eval suites
.\scripts\evals.ps1 -Gate    # also run the offline gate; non-zero exit on failure (CI gate)
.\scripts\test.ps1           # full suite incl. evals + compliance
```

**API** (operator+)

```
POST /evals/run    { "suite": "all" | "<suite>", "mode": "offline" | "online" }
GET  /evals/history?suite=<suite>
```

## Online vs offline

- **offline** (default): deterministic scenarios; the CI quality gate.
- **online**: same scenarios wired to live subsystems (retriever, sandbox, memory,
  agent results) via the API's eval context — used for canary/production checks.

## CI integration

`evals.ps1 -Gate` returns a non-zero exit code when the aggregate gate is not met,
so it can block a release step. `test.ps1` runs unit + integration + e2e + security
+ evals + compliance and fails fast on any suite.

## Extending

Add a scenario suite in `packages/evals/jarvis_evals/scenarios.py` as
`fn(context) -> list[(name, score)]` and register it in `SUITES`. It is
automatically included in `run_all`, the gate, and history.
