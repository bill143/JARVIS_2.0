"""DIRECTOR crew management loop (ECHO Command agent map v3).

DIRECTOR (senior construction executive) assigns a task with acceptance
criteria -> a worker (ESTIMATOR or BIDS) executes -> DIRECTOR verifies
against the criteria (max 2 rework cycles) -> JARVIS delivers.

Guarantees:
- Every step writes one activity-log row on completion; the row's detail is
  the Hermes §7 message JSON: task_id, parent_task_id, status, model_used,
  tokens_used, latency_ms.
- Workers are text-only and report to DIRECTOR only (they have no tool or
  voice access here by construction).
- DIRECTOR never executes work itself and never sends anything externally;
  external commitments require Bill's sign-off (persona-enforced and there is
  no external channel in this module at all).
- TRADER is fully isolated: it is not a crew member and cannot be assigned.
"""

from __future__ import annotations

import json
import time
import uuid
from functools import lru_cache
from pathlib import Path

import yaml

from jarvis_observability.activity import get_activity_log
from jarvis_shared.logging import get_logger, log_event
from jarvis_shared.schemas import Message

logger = get_logger("jarvis.agents.crew")

MAX_REWORK_CYCLES = 2
WORKERS = ("ESTIMATOR", "BIDS")

_REPO_ROOT = Path(__file__).resolve().parents[4]
_AGENTS_YAML = _REPO_ROOT / "configs" / "agents.yaml"


@lru_cache(maxsize=1)
def load_agent_registry() -> dict:
    data = yaml.safe_load(_AGENTS_YAML.read_text(encoding="utf-8")) or {}
    return data.get("agents", {})


@lru_cache(maxsize=8)
def _knowledge(worker: str) -> str:
    rel = load_agent_registry().get(worker, {}).get("knowledge", "")
    if not rel:
        return ""
    path = _REPO_ROOT / rel
    return path.read_text(encoding="utf-8")[:8000] if path.is_file() else ""


def _hermes(task_id: str, parent: str, status: str, model: str, tokens: int, latency_ms: float) -> str:
    return json.dumps({
        "task_id": task_id,
        "parent_task_id": parent,
        "status": status,
        "model_used": model,
        "tokens_used": tokens,
        "latency_ms": round(latency_ms, 1),
    })


def _est_tokens(*texts: str) -> int:
    return max(sum(len(t) for t in texts) // 4, 1)


class CrewManager:
    """Runs the assign -> execute -> verify -> deliver loop with real model calls."""

    def __init__(self, router):
        self.router = router  # jarvis_adapters ProviderRouter

    async def _model(self, system: str, user: str) -> tuple[str, str, int, float]:
        t0 = time.perf_counter()
        resp = await self.router.complete([
            Message(role="system", content=system),
            Message(role="user", content=user),
        ])
        latency = (time.perf_counter() - t0) * 1000
        text = resp.content or ""
        return text, resp.model, _est_tokens(system, user, text), latency

    async def run_task(self, worker: str, task: str, acceptance_criteria: str, requested_by: str) -> dict:
        registry = load_agent_registry()
        if worker not in WORKERS:
            # TRADER isolation and unknown workers are hard-rejected, never coerced.
            raise ValueError(f"'{worker}' is not a crew worker (crew: {', '.join(WORKERS)})")

        activity = get_activity_log()
        task_id = uuid.uuid4().hex[:12]
        director_persona = registry.get("DIRECTOR", {}).get("persona", "")
        worker_persona = registry.get(worker, {}).get("persona", "")

        # -- DIRECTOR assigns ------------------------------------------------
        activity.record(
            "DIRECTOR", f"assign {worker}: {task[:160]}", "completed",
            detail=_hermes(task_id, "", "assigned", "", 0, 0.0),
        )

        cycles: list[dict] = []
        feedback = ""
        accepted = False
        output = ""
        for cycle in range(MAX_REWORK_CYCLES + 1):
            sub_id = f"{task_id}.{cycle + 1}"

            # -- worker executes (text-only, reports to DIRECTOR) ------------
            prompt = (
                f"Assignment from the DIRECTOR:\n{task}\n\n"
                f"Acceptance criteria:\n{acceptance_criteria}\n"
                + (f"\nRework feedback from the DIRECTOR on your previous attempt:\n{feedback}\n" if feedback else "")
            )
            try:
                output, model, tokens, latency = await self._model(
                    f"{worker_persona}\n\n{_knowledge(worker)}", prompt,
                )
                activity.record(
                    worker, f"execute: {task[:160]}", "completed", model=model,
                    detail=_hermes(sub_id, task_id, "executed", model, tokens, latency),
                )
            except Exception as exc:
                activity.record(
                    worker, f"execute: {task[:160]}", "failed",
                    detail=_hermes(sub_id, task_id, f"error: {exc}"[:120], "", 0, 0.0),
                )
                raise

            # -- DIRECTOR verifies -------------------------------------------
            verify_prompt = (
                f"Task assigned to {worker}: {task}\n\n"
                f"Acceptance criteria:\n{acceptance_criteria}\n\n"
                f"Worker deliverable:\n{output[:6000]}\n\n"
                'Verify strictly against the criteria. Reply with JSON only: '
                '{"accept": true|false, "feedback": "<what is missing or wrong, empty if accepted>"}'
            )
            verdict_text, vmodel, vtokens, vlatency = await self._model(director_persona, verify_prompt)
            accepted, feedback = _parse_verdict(verdict_text)
            activity.record(
                "DIRECTOR", f"verify {worker}: {task[:160]}",
                "completed" if accepted else "failed", model=vmodel,
                detail=_hermes(sub_id, task_id, "accepted" if accepted else "rework", vmodel, vtokens, vlatency),
            )
            cycles.append({"cycle": cycle + 1, "accepted": accepted, "feedback": feedback})
            if accepted:
                break

        # -- JARVIS delivers -------------------------------------------------
        final_status = "completed" if accepted else "failed"
        activity.record(
            "JARVIS", f"deliver: {task[:160]}", final_status,
            detail=_hermes(task_id, "", "delivered" if accepted else "returned_unaccepted", "", 0, 0.0),
        )
        log_event(logger, "crew.task_done", task_id=task_id, worker=worker,
                  accepted=accepted, cycles=len(cycles), requested_by=requested_by)
        return {
            "task_id": task_id,
            "worker": worker,
            "task": task,
            "acceptance_criteria": acceptance_criteria,
            "accepted": accepted,
            "cycles": cycles,
            "deliverable": output,
            "note": "" if accepted else
                    f"not accepted after {MAX_REWORK_CYCLES} rework cycles — needs Bill's decision",
        }


def _parse_verdict(text: str) -> tuple[bool, str]:
    """Parse the DIRECTOR's JSON verdict; malformed output counts as rework."""
    try:
        start, end = text.find("{"), text.rfind("}")
        data = json.loads(text[start:end + 1])
        return bool(data.get("accept")), str(data.get("feedback", ""))[:1000]
    except (ValueError, AttributeError):
        return False, f"verifier returned malformed verdict: {text[:200]}"
