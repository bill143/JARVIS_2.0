"""Multi-agent orchestrator: coordinator runs bounded debate rounds across
specialists, then arbitrates (critic override / majority vote). All messages are
persisted to the bus for traceability. Falls back to single-agent mode on error.
"""

from __future__ import annotations

from jarvis_agents.bus import MessageBus
from jarvis_agents.roles import ROLE_PROMPTS, Coder, MemorySteward, Researcher, Verifier
from jarvis_observability.activity import get_activity_log
from jarvis_shared.logging import get_logger, log_event

logger = get_logger("jarvis.agents")


class MultiAgentOrchestrator:
    def __init__(self, bus: MessageBus, *, retriever=None, tool_runner=None, audit=None,
                 max_rounds: int = 3, arbitration: str = "critic-override"):
        self.bus = bus
        self.retriever = retriever
        self.tool_runner = tool_runner
        self.audit = audit
        self.max_rounds = max_rounds
        self.arbitration = arbitration

    async def run(self, goal: str, *, owner: str, tenant: str) -> dict:
        session_id = self.bus.create_session(goal, owner, tenant)
        self.bus.post(session_id, sender="coordinator", role="coordinator",
                      content=f"{ROLE_PROMPTS['coordinator']} Goal: {goal}")
        try:
            researcher = Researcher(retriever=self.retriever)
            coder = Coder(tool_runner=self.tool_runner)
            verifier = Verifier()
            steward = MemorySteward()

            context: dict = {}
            final_verdict = "revise"
            rnd = 0
            for rnd in range(1, self.max_rounds + 1):
                r = await researcher.act(goal, context)
                context["researcher"] = r
                self.bus.post(session_id, sender="researcher", role="researcher", round=rnd,
                              content=r["finding"], meta={"confidence": r["confidence"], "citations": len(r["citations"])})

                c = await coder.act(goal, context)
                context["coder"] = c
                self.bus.post(session_id, sender="coder", role="coder", round=rnd,
                              content=str(c["result"])[:400], meta={"confidence": c["confidence"]})

                v = await verifier.act(goal, context)
                context["verifier"] = v
                self.bus.post(session_id, sender="verifier", role="verifier", round=rnd,
                              content=f"verdict={v['verdict']} issues={v['issues']}", meta={"confidence": v["confidence"]})
                final_verdict = v["verdict"]
                if v["verdict"] == "approve":
                    break  # bounded debate: stop early on approval

            steward_out = await steward.act(goal, context)
            self.bus.post(session_id, sender="memory_steward", role="memory_steward",
                          content=f"should_store={steward_out['should_store']}", meta={"confidence": steward_out["confidence"]})

            # arbitration
            if self.arbitration == "critic-override":
                decided = final_verdict == "approve"
                arb_note = "critic (verifier) override"
            else:  # majority vote across specialists' confidence >= 0.5
                votes = [context.get(k, {}).get("confidence", 0) >= 0.5 for k in ("researcher", "coder", "verifier")]
                decided = sum(votes) >= 2
                arb_note = f"majority vote {sum(votes)}/3"

            result = {
                "goal": goal, "decision": "approved" if decided else "needs_revision",
                "arbitration": arb_note, "answer": context.get("researcher", {}).get("finding", ""),
                "citations": context.get("researcher", {}).get("citations", []),
                "verifier": context.get("verifier", {}), "memory_recommendation": steward_out,
                "rounds": rnd, "mode": "multi-agent",
            }
            self.bus.finish(session_id, arb_note, result)
            if self.audit:
                self.audit.record("agents", "session_completed", actor=owner, tenant=tenant,
                                  detail={"session_id": session_id, "decision": result["decision"], "rounds": rnd})
            log_event(logger, "agents.completed", session_id=session_id, decision=result["decision"], rounds=rnd)
            # Agent map v3: ORCHESTRATOR merged into JARVIS — multi-agent
            # debate is a JARVIS capability, not a separate agent identity.
            get_activity_log().record(
                "JARVIS", goal[:200], "completed",
                detail=f"multi-agent decision={result['decision']} rounds={rnd} arbitration={arb_note}",
            )
            return {"session_id": session_id, **result}

        except Exception as exc:
            # failure fallback to single-agent mode
            log_event(logger, "agents.fallback_single", session_id=session_id, error=str(exc))
            single = await Researcher(retriever=self.retriever).act(goal, {})
            result = {"goal": goal, "decision": "single-agent-fallback", "answer": single["finding"],
                      "citations": single["citations"], "mode": "single-agent", "error": str(exc)}
            self.bus.finish(session_id, "fallback", result)
            get_activity_log().record(
                "JARVIS", goal[:200], "failed",
                detail=f"multi-agent error, single-agent fallback served: {exc}"[:400],
            )
            return {"session_id": session_id, **result}
