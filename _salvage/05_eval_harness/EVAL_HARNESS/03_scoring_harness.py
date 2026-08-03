#!/usr/bin/env python3
"""
NEXUS GOAT Agent Scoring Harness
================================

Runs eval cases from nexus_memory.eval_cases against an LLM agent,
scores responses against the rubric, and writes graded results to
nexus_memory.eval_runs in Supabase.

Usage:
    # Required env vars:
    export SUPABASE_URL="https://qulvniixtxxyppmhufha.supabase.co"
    export SUPABASE_SERVICE_ROLE_KEY="<service-role-key>"
    export ANTHROPIC_API_KEY="<your-anthropic-key>"   # for Claude
    export OPENAI_API_KEY="<your-openai-key>"         # optional, for GPT
    export GOOGLE_API_KEY="<your-google-key>"         # optional, for Gemini

    # Run all 30 cases against the CEO agent (Claude Opus 4.7):
    python scoring_harness.py --agent-code CEO-001 --pillar all

    # Run a specific case:
    python scoring_harness.py --case-id P1-01 --agent-code CEO-001

    # Dry run (no DB writes):
    python scoring_harness.py --case-id P1-01 --agent-code CEO-001 --dry-run

    # Export Grafana-friendly metrics CSV:
    python scoring_harness.py --export-metrics --output metrics.csv
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from dataclasses import dataclass, field, asdict
from typing import Any

import requests

SUPABASE_URL = os.environ.get("SUPABASE_URL", "").rstrip("/")
SERVICE_ROLE = os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "")
ANTHROPIC_KEY = os.environ.get("ANTHROPIC_API_KEY", "")
OPENAI_KEY = os.environ.get("OPENAI_API_KEY", "")
GOOGLE_KEY = os.environ.get("GOOGLE_API_KEY", "")

PASS_THRESHOLD = 0.70  # 70% of max points required to "pass" a case


# ---------------------------------------------------------------------------
# Supabase REST helpers (no extra dependencies — service-role JWT is enough)
# ---------------------------------------------------------------------------

def sb_headers() -> dict[str, str]:
    if not SUPABASE_URL or not SERVICE_ROLE:
        raise RuntimeError("SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY must be set")
    return {
        "apikey": SERVICE_ROLE,
        "Authorization": f"Bearer {SERVICE_ROLE}",
        "Content-Type": "application/json",
        "Accept-Profile": "nexus_memory",
        "Content-Profile": "nexus_memory",
        "Prefer": "return=representation",
    }


def sb_get(path: str, params: dict[str, str] | None = None) -> list[dict]:
    r = requests.get(f"{SUPABASE_URL}/rest/v1/{path}", headers=sb_headers(),
                     params=params, timeout=30)
    r.raise_for_status()
    return r.json()


def sb_post(path: str, body: dict | list[dict]) -> list[dict]:
    r = requests.post(f"{SUPABASE_URL}/rest/v1/{path}", headers=sb_headers(),
                      data=json.dumps(body), timeout=30)
    r.raise_for_status()
    return r.json()


# ---------------------------------------------------------------------------
# Case loading
# ---------------------------------------------------------------------------

def load_cases(pillar: str | None = None, case_id: str | None = None) -> list[dict]:
    params = {"select": "*", "order": "external_id.asc"}
    if case_id:
        params["external_id"] = f"eq.{case_id}"
    elif pillar and pillar != "all":
        params["pillar"] = f"eq.{pillar}"
    return sb_get("eval_cases", params)


def load_agent(agent_code: str) -> dict:
    rows = sb_get("agent_profiles", {"select": "*", "agent_code": f"eq.{agent_code}"})
    if not rows:
        raise RuntimeError(f"Agent not found: {agent_code}")
    return rows[0]


# ---------------------------------------------------------------------------
# Model dispatch — agnostic call layer
# ---------------------------------------------------------------------------

def call_claude(model: str, prompt: str) -> str:
    if not ANTHROPIC_KEY:
        raise RuntimeError("ANTHROPIC_API_KEY not set")
    r = requests.post(
        "https://api.anthropic.com/v1/messages",
        headers={
            "x-api-key": ANTHROPIC_KEY,
            "anthropic-version": "2023-06-01",
            "content-type": "application/json",
        },
        json={
            "model": model,
            "max_tokens": 2048,
            "messages": [{"role": "user", "content": prompt}],
        },
        timeout=120,
    )
    r.raise_for_status()
    data = r.json()
    return "".join(blk.get("text", "") for blk in data.get("content", []))


def call_openai(model: str, prompt: str) -> str:
    if not OPENAI_KEY:
        raise RuntimeError("OPENAI_API_KEY not set")
    r = requests.post(
        "https://api.openai.com/v1/chat/completions",
        headers={"Authorization": f"Bearer {OPENAI_KEY}",
                 "Content-Type": "application/json"},
        json={"model": model,
              "messages": [{"role": "user", "content": prompt}],
              "max_completion_tokens": 2048},
        timeout=120,
    )
    r.raise_for_status()
    return r.json()["choices"][0]["message"]["content"]


def call_gemini(model: str, prompt: str) -> str:
    if not GOOGLE_KEY:
        raise RuntimeError("GOOGLE_API_KEY not set")
    r = requests.post(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        params={"key": GOOGLE_KEY},
        json={"contents": [{"parts": [{"text": prompt}]}],
              "generationConfig": {"maxOutputTokens": 2048}},
        timeout=120,
    )
    r.raise_for_status()
    cand = r.json()["candidates"][0]
    return "".join(p.get("text", "") for p in cand["content"]["parts"])


def dispatch(model: str, prompt: str) -> str:
    if model.startswith("claude"):
        return call_claude(model, prompt)
    if model.startswith("gpt") or model.startswith("o"):
        return call_openai(model, prompt)
    if model.startswith("gemini"):
        return call_gemini(model, prompt)
    raise RuntimeError(f"Unknown model family: {model}")


# ---------------------------------------------------------------------------
# Prompt assembly + scoring
# ---------------------------------------------------------------------------

ROLE_GUIDANCE = {
    "ceo": "You are the CEO of a 229-agent hierarchical orchestration system. You only delegate to the Executive — never to Workers directly. You apply HITL gating to high-impact decisions.",
    "executive": "You are the Executive. You compress Worker logs into structured summaries and allocate work to Project Managers. You do not micromanage Workers.",
    "project_manager": "You are a Project Manager. You translate Executive goals into department plans and assign work to Project Engineers — never directly to Workers.",
    "project_engineer": "You are a Project Engineer. You decompose tasks into specifications and assign work to Superintendents. You enforce QA gates.",
    "superintendent": "You are a Superintendent. You supervise ~8 Workers in real time, enforce sync logic, and resolve immediate constraints.",
    "worker": "You are a Worker. You execute tasks within scope, report status with evidence, and provide concrete next steps.",
}


def build_prompt(case: dict, agent: dict) -> str:
    level_key = agent["level"]
    role_guidance = ROLE_GUIDANCE.get(level_key, "")
    expected = case["expected_output"]
    rubric_names = [c["name"] for c in case["rubric"]["criteria"]]

    parts = [
        f"# Role\n{role_guidance}",
        f"# Task\n{case['input']['task']}",
    ]

    if "context" in case["input"]:
        parts.append(f"# Context\n{json.dumps(case['input']['context'], indent=2)}")
    if "rag_context" in case["input"]:
        parts.append(f"# Retrieved Context (use ONLY this for factual claims)\n"
                     f"{json.dumps(case['input']['rag_context'], indent=2)}")
    if "memory" in case["input"]:
        parts.append(f"# Memory\n{json.dumps(case['input']['memory'], indent=2)}")

    parts.append("# Required Output Sections")
    parts.extend(f"- {item}" for item in expected.get("must_include", []))
    if expected.get("must_not_include"):
        parts.append("# Must NOT Include")
        parts.extend(f"- {item}" for item in expected["must_not_include"])

    parts.append("# Evaluation Criteria You Will Be Scored On")
    parts.extend(f"- {n}" for n in rubric_names)

    parts.append("\nProvide your response now.")
    return "\n\n".join(parts)


def score_response(response: str, case: dict) -> tuple[dict, float, float, list[str]]:
    """Score response against rubric. Returns (per_criterion_scores, total, max, failure_modes_hit)."""
    response_lower = response.lower()
    must_include = case["expected_output"].get("must_include", [])
    must_not_include = case["expected_output"].get("must_not_include", [])
    rubric_criteria = case["rubric"]["criteria"]
    failure_modes = case.get("failure_modes", [])

    # Per-criterion scoring (0/1/2): count how many checks per criterion fired
    scores: dict[str, float] = {}
    total = 0.0
    max_total = 0.0
    for crit in rubric_criteria:
        weight = float(crit.get("weight", 2))
        max_total += weight
        check_hits = 0
        for chk in crit["checks"]:
            # Heuristic: extract distinctive keywords from the check text
            keywords = _keywords(chk)
            if keywords and any(kw in response_lower for kw in keywords):
                check_hits += 1
        ratio = check_hits / max(1, len(crit["checks"]))
        # Map ratio to 0/1/2-style scoring proportional to weight
        score = round(ratio * weight, 2)
        # Bonus: must_include presence boosts criteria when relevant
        scores[crit["name"]] = score
        total += score

    # Must-include / must-not-include adjustments
    must_include_hits = sum(1 for s in must_include if _keywords_hit(s, response_lower))
    must_include_ratio = must_include_hits / max(1, len(must_include))
    if must_include_ratio < 0.5:
        total *= 0.7  # heavy penalty for missing key sections

    for forbidden in must_not_include:
        if _keywords_hit(forbidden, response_lower):
            total *= 0.5  # penalize hallucinations / forbidden content

    # Failure modes hit detection
    fm_hits = [fm for fm in failure_modes if _failure_mode_hit(fm, response_lower)]
    if fm_hits:
        total = max(0.0, total - len(fm_hits))

    return scores, round(min(total, max_total), 2), max_total, fm_hits


_TOKEN_RE = re.compile(r"[a-zA-Z][a-zA-Z0-9_-]{2,}")
_STOP = {"the", "and", "for", "with", "that", "this", "from", "into", "must",
         "any", "all", "not", "are", "has", "have", "will", "should", "use",
         "uses", "include", "includes", "mentions", "calls", "out", "via",
         "per", "least", "more", "than", "without", "about", "every", "each"}


def _keywords(text: str) -> list[str]:
    """Extract distinctive lowercase tokens from a check string."""
    toks = [t.lower() for t in _TOKEN_RE.findall(text)]
    return [t for t in toks if t not in _STOP][:3] or toks[:1]


def _keywords_hit(text: str, response_lower: str) -> bool:
    """Loose match for must_include / must_not_include scanning."""
    kws = _keywords(text)
    return any(kw in response_lower for kw in kws) if kws else False


def _failure_mode_hit(failure_text: str, response_lower: str) -> bool:
    """Stricter match for failure-mode detection.

    Failure modes are descriptions of bad behavior, so they must match
    multiple distinctive keywords to count as a hit. This avoids false
    positives like 'failover' triggering on the phrase 'Ignores failover'
    when the response actually addresses failover correctly.
    """
    kws = _keywords(failure_text)
    if len(kws) < 2:
        return False
    hits = sum(1 for kw in kws if kw in response_lower)
    return hits >= 2


# ---------------------------------------------------------------------------
# Run + persist
# ---------------------------------------------------------------------------

@dataclass
class RunResult:
    case_id: str
    external_id: str
    agent_code: str
    model_used: str
    scores: dict
    total_score: float
    max_score: float
    passed: bool
    failure_modes_hit: list[str]
    duration_ms: int
    response: str = ""


def run_case(case: dict, agent: dict, dry_run: bool = False) -> RunResult:
    prompt = build_prompt(case, agent)
    start = time.time()
    try:
        response = dispatch(agent["model"], prompt)
    except Exception as e:
        response = f"<<MODEL_CALL_FAILED: {e}>>"
    duration_ms = int((time.time() - start) * 1000)

    scores, total, max_total, fm_hits = score_response(response, case)
    passed = (total / max_total >= PASS_THRESHOLD) if max_total > 0 else False

    result = RunResult(
        case_id=case["case_id"],
        external_id=case["external_id"],
        agent_code=agent["agent_code"],
        model_used=agent["model"],
        scores=scores,
        total_score=total,
        max_score=max_total,
        passed=passed,
        failure_modes_hit=fm_hits,
        duration_ms=duration_ms,
        response=response,
    )

    if not dry_run:
        sb_post("eval_runs", {
            "case_id": case["case_id"],
            "agent_id": agent["agent_id"],
            "model_used": agent["model"],
            "agent_response": {"text": response},
            "scores": scores,
            "total_score": total,
            "max_score": max_total,
            "passed": passed,
            "failure_modes_hit": fm_hits,
            "duration_ms": duration_ms,
        })

    return result


# ---------------------------------------------------------------------------
# Metrics export (Grafana-friendly CSV)
# ---------------------------------------------------------------------------

def export_metrics(output_path: str) -> None:
    runs = sb_get("eval_runs", {"select": "*,eval_cases(external_id,pillar,pillar_name,role,level)",
                                "order": "created_at.desc"})
    headers = ["timestamp", "case_external_id", "pillar", "pillar_name", "role",
               "level", "model_used", "total_score", "max_score", "score_pct",
               "passed", "duration_ms", "failure_count"]
    with open(output_path, "w") as f:
        f.write(",".join(headers) + "\n")
        for r in runs:
            c = r.get("eval_cases") or {}
            pct = (float(r["total_score"]) / float(r["max_score"]) * 100) if r["max_score"] else 0
            row = [
                r.get("created_at", ""), c.get("external_id", ""), str(c.get("pillar", "")),
                c.get("pillar_name", ""), c.get("role", ""), str(c.get("level", "")),
                r.get("model_used", ""), str(r["total_score"]), str(r["max_score"]),
                f"{pct:.1f}", str(r["passed"]).lower(), str(r["duration_ms"]),
                str(len(r.get("failure_modes_hit") or [])),
            ]
            f.write(",".join(f'"{v}"' for v in row) + "\n")
    print(f"Wrote metrics → {output_path} ({len(runs)} runs)")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> int:
    p = argparse.ArgumentParser(description="NEXUS GOAT Agent Scoring Harness")
    p.add_argument("--case-id", help="Run a specific case (e.g., P1-01)")
    p.add_argument("--pillar", default="all", help="Filter by pillar (1-6) or 'all'")
    p.add_argument("--agent-code", help="Agent profile code (e.g., CEO-001)")
    p.add_argument("--dry-run", action="store_true", help="Don't write to DB")
    p.add_argument("--export-metrics", action="store_true", help="Export metrics CSV")
    p.add_argument("--output", default="eval_metrics.csv")
    args = p.parse_args()

    if args.export_metrics:
        export_metrics(args.output)
        return 0

    if not args.agent_code:
        print("--agent-code required (e.g., --agent-code CEO-001)")
        return 1

    agent = load_agent(args.agent_code)
    cases = load_cases(pillar=args.pillar, case_id=args.case_id)
    if not cases:
        print("No cases matched filter.")
        return 1

    print(f"Running {len(cases)} case(s) against {agent['agent_code']} ({agent['model']})...")
    summary = {"pass": 0, "fail": 0, "total_pct": 0.0}
    for case in cases:
        res = run_case(case, agent, dry_run=args.dry_run)
        pct = (res.total_score / res.max_score * 100) if res.max_score else 0
        marker = "✅" if res.passed else "❌"
        print(f"  {marker} {res.external_id} | {res.total_score}/{res.max_score} "
              f"({pct:.0f}%) | {res.duration_ms}ms | failures: {len(res.failure_modes_hit)}")
        summary["pass" if res.passed else "fail"] += 1
        summary["total_pct"] += pct

    avg_pct = summary["total_pct"] / len(cases) if cases else 0
    print(f"\nSummary: {summary['pass']}/{len(cases)} passed | avg score {avg_pct:.1f}%")
    return 0


if __name__ == "__main__":
    sys.exit(main())
