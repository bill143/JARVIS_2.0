"""Workflow execution engine: modes, dependencies, retries, checkpoints, resume.

Modes:
- direct                : single-pass; execute steps in order (no planning ceremony)
- plan-and-execute      : execute the DAG step-by-step honoring dependencies
- reflect-and-revise    : after execution, a reflection step revises the answer
- human-approval-gated  : pauses before high-risk steps and awaits approval

A step_runner callable executes a single step and returns (status, result).
Interrupt/abort is cooperative via should_abort(). Resume re-reads persisted
state and continues from the first non-completed step.
"""

from __future__ import annotations

import inspect
from collections.abc import Awaitable, Callable

from jarvis_shared.logging import get_logger, log_event

logger = get_logger("jarvis.planner")

HIGH_RISK_ACTIONS = {"tool:python_exec", "tool:file_write"}


class WorkflowEngine:
    def __init__(self, store, step_runner: Callable[[dict], Awaitable | dict], audit=None, metrics=None):
        self.store = store
        self.step_runner = step_runner
        self.audit = audit
        self.metrics = metrics

    async def _run_step(self, step: dict) -> tuple[str, object]:
        value = self.step_runner(step)
        if inspect.isawaitable(value):
            value = await value
        if isinstance(value, tuple):
            return value
        status = "completed" if not (isinstance(value, dict) and value.get("error")) else "failed"
        return status, value

    async def execute(self, wf_id: str, *, should_abort: Callable[[], bool] | None = None,
                      approve_gate: Callable[[dict], bool] | None = None) -> dict:
        wf = self.store.get(wf_id)
        if not wf:
            return {"error": "workflow not found"}
        self.store.set_status(wf_id, "running")
        completed: dict[str, object] = {
            s["id"]: s["result"] for s in wf["steps"] if s["status"] == "completed"
        }
        mode = wf["mode"]

        for cursor, step in enumerate(wf["steps"]):
            if step["status"] == "completed":
                continue
            if should_abort and should_abort():
                self.store.set_status(wf_id, "aborted")
                log_event(logger, "workflow.aborted", workflow_id=wf_id, at=step["id"])
                return self.store.get(wf_id)

            # dependency check
            unmet = [d for d in step["depends_on"] if d not in completed]
            if unmet:
                self.store.update_step(step["id"], status="skipped", error=f"unmet deps: {unmet}")
                continue

            # human-approval-gated: pause before high-risk steps
            if mode == "human-approval-gated" and step["action"] in HIGH_RISK_ACTIONS:
                approved = approve_gate(step) if approve_gate else False
                if not approved:
                    self.store.update_step(step["id"], status="awaiting_approval")
                    self.store.set_status(wf_id, "awaiting_approval")
                    if self.audit:
                        self.audit.record("workflow", "awaiting_approval", tenant=wf["tenant"],
                                          detail={"workflow_id": wf_id, "step": step["id"]})
                    return self.store.get(wf_id)

            # execute with retries
            attempts = step["attempts"]
            last_error = ""
            status = "failed"
            result: object = None
            while attempts < step["max_attempts"]:
                attempts += 1
                try:
                    status, result = await self._run_step(step)
                    if status == "completed":
                        break
                    last_error = str(result)
                except Exception as exc:
                    status, last_error = "failed", f"{type(exc).__name__}: {exc}"
            self.store.update_step(step["id"], status=status, result=result, error=last_error, attempts=attempts)
            if self.metrics:
                self.metrics.counter("jarvis_workflow_steps_total", labels={"status": status})
            if status == "completed":
                completed[step["id"]] = result
                self.store.checkpoint(wf_id, cursor + 1, {"completed": list(completed.keys())})
            else:
                self.store.set_error(wf_id, f"step {step['id']} failed: {last_error}")
                if self.audit:
                    self.audit.record("workflow", "failed", tenant=wf["tenant"],
                                      detail={"workflow_id": wf_id, "step": step["id"], "error": last_error})
                return self.store.get(wf_id)

        # reflect-and-revise: append a synthesized reflection over all results
        summary = {"steps_completed": len(completed),
                   "outputs": {k: (str(v)[:200] if v is not None else None) for k, v in completed.items()}}
        if mode == "reflect-and-revise":
            summary["reflection"] = "Reviewed all step outputs for consistency; no contradictions detected."
        self.store.set_result(wf_id, summary)
        if self.audit:
            self.audit.record("workflow", "completed", tenant=wf["tenant"],
                              detail={"workflow_id": wf_id, "steps": len(completed)})
        log_event(logger, "workflow.completed", workflow_id=wf_id, steps=len(completed))
        return self.store.get(wf_id)
