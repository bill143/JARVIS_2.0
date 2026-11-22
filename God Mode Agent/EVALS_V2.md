# Evals v2

A production-grade evaluation platform for the God Mode Agent: per-run / per-suite /
per-case results, reproducibility metadata, a weighted release gate, trend history,
and failure drill-down with "where to investigate" links.

**Additive & namespaced.** All v2 endpoints live under `/evals/v2/*` and are served
by the shim entrypoint (`jarvis_api.serve_openai:app`, i.e. `scripts/dev-api-openai.ps1`).
The legacy `/evals/run` and `/evals/history` are unchanged.

## Concepts

- **Suite** — a scenario group (reasoning, tool_correctness, hallucination_resistance,
  citation_fidelity, injection_resilience, memory_correctness, arbitration_quality).
- **Case** — an individual scored check inside a suite (score in [0,1]).
- **Run** — one execution across selected suites, with reproducibility metadata
  (commit, branch, app version, env profile, dataset version, provider/model, who triggered it).
- **Gate** — the release decision computed from suite scores and the gate policy.

## Gate policy semantics

Stored in `eval_gate_policy` (single `default` row), editable by admins:

| Field | Meaning |
|---|---|
| `block_deploy_on_fail` | If true, a failing gate is reported as **BLOCKED** (deploy should stop). |
| `global_threshold` | Minimum weighted-overall score to pass (default from `eval_pass_threshold`). |
| `per_suite_threshold` | Optional per-suite overrides of the pass threshold. |
| `per_suite_weight` | Optional per-suite weights for the weighted overall (default 1.0). |
| `critical_suites` | Suites that **hard-fail** the gate if they miss their threshold, regardless of overall (default: `injection_resilience`, `citation_fidelity`). |

**Decision:** `weighted_overall = Σ(weight_i · score_i) / Σ(weight_i)`. The gate passes
when `weighted_overall ≥ global_threshold` **and** every critical suite meets its
threshold. Each run stores a human-readable `gate_explanation` listing exactly what
failed and which threshold was missed.

## Endpoints

| Method | Path | Role | Notes |
|---|---|---|---|
| GET | `/evals/v2/suites` | operator | Suite catalog + scope + investigate links |
| POST | `/evals/v2/run` | operator | Run all (or a `suites` subset). Accepts `Idempotency-Key`. |
| POST | `/evals/v2/run/{suite}` | operator | Run one suite |
| GET | `/evals/v2/runs` | operator | History; filters: `status`, `below_score`, `suite`, `date_from`, `date_to`, `limit`, `offset` |
| GET | `/evals/v2/runs/{id}` | operator | Run summary + suites + gate |
| GET | `/evals/v2/runs/{id}/suites` | operator | Suite rows |
| GET | `/evals/v2/runs/{id}/suites/{suite}/cases` | operator | Cases (`only_failed=true` for failures) |
| GET | `/evals/v2/trends` | operator | Overall + per-suite time series |
| GET | `/evals/v2/gate-policy` | operator | Current policy |
| PUT | `/evals/v2/gate-policy` | admin | Update thresholds/weights/critical/block toggle (audited) |
| POST | `/evals/v2/runs/{id}/rerun` | operator | Re-run the exact configuration (audited) |

All mutating actions are written to the hash-chained audit log. RBAC: operator to
run/read, admin to change gate policy.

## Examples

Run everything:
```bash
curl -X POST http://127.0.0.1:8000/evals/v2/run -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"branch":"feat/god-mode-agent","commit_sha":"<sha>"}'
```

Run a single suite:
```bash
curl -X POST http://127.0.0.1:8000/evals/v2/run/injection_resilience -H "Authorization: Bearer $TOKEN" -d '{}'
```

Interpreting a **BLOCKED** gate: open the run, read `gate_explanation` — e.g.
`"Critical suite 'citation_fidelity' scored 0.5 < threshold 0.9 (hard-fail)."` Then
use the suite's **investigate links** (test scenario + source module) from the detail
drawer to triage, e.g. `packages/rag/jarvis_rag` and
`packages/evals/jarvis_evals/scenarios.py (_citation_fidelity)`.

Rerun the exact configuration:
```bash
curl -X POST http://127.0.0.1:8000/evals/v2/runs/<run_id>/rerun -H "Authorization: Bearer $TOKEN"
```

## Notes / honest limitations

- Suites run **synchronously** (they are deterministic and fast). Async queue
  execution is a future enhancement; a concurrent-identical-run lock prevents
  duplicate simultaneous runs today.
- In offline mode there are no provider calls, so `token_*` / `estimated_cost_usd`
  are **deterministic estimates** (labeled as such in the UI), not billed spend.
