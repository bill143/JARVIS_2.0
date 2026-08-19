"""Role-based prompt templates + deterministic specialist behaviors.

Each specialist has a role prompt and a deterministic `act` for offline/test use;
when a live model router + tools are available the orchestrator can substitute
real completions behind the same interface.
"""

from __future__ import annotations

ROLE_PROMPTS = {
    "coordinator": "You are the Coordinator. Decompose the goal, delegate to specialists, and synthesize a final answer.",
    "researcher": "You are the Researcher. Retrieve grounded evidence with citations; never fabricate sources.",
    "coder": "You are the Coder/Toolsmith. Execute tools and reason about code; return concrete results.",
    "verifier": "You are the Verifier/Critic. Fact-check, assess safety and quality, and flag issues.",
    "memory_steward": "You are the Memory Steward. Decide what to remember, with confidence and provenance.",
}


class Specialist:
    role = "base"

    def __init__(self, retriever=None, tool_runner=None):
        self.retriever = retriever
        self.tool_runner = tool_runner

    async def act(self, goal: str, context: dict) -> dict:
        raise NotImplementedError


class Researcher(Specialist):
    role = "researcher"

    async def act(self, goal: str, context: dict) -> dict:
        if self.retriever is not None:
            try:
                ans = self.retriever(goal)
                return {"role": self.role, "finding": ans.get("answer", ""),
                        "citations": ans.get("citations", []), "confidence": ans.get("confidence", 0.0)}
            except Exception as exc:
                return {"role": self.role, "finding": f"retrieval failed: {exc}",
                        "citations": [], "confidence": 0.0}
        return {"role": self.role, "finding": f"No knowledge base; goal noted: {goal[:120]}",
                "citations": [], "confidence": 0.3}


class Coder(Specialist):
    role = "coder"

    async def act(self, goal: str, context: dict) -> dict:
        if self.tool_runner is not None:
            result = await self.tool_runner(goal)
            return {"role": self.role, "result": result, "confidence": 0.8 if result.get("status") == "ok" else 0.4}
        return {"role": self.role, "result": {"note": "no tools available"}, "confidence": 0.4}


class Verifier(Specialist):
    role = "verifier"

    async def act(self, goal: str, context: dict) -> dict:
        # Deterministic critique: agreement if researcher confidence is decent and
        # coder produced a result; otherwise request revision.
        research = context.get("researcher", {})
        coder = context.get("coder", {})
        confident = research.get("confidence", 0) >= 0.4 or bool(coder.get("result"))
        cited = bool(research.get("citations"))
        verdict = "approve" if confident else "revise"
        issues = []
        if not cited:
            issues.append("no citations from researcher")
        if not coder.get("result"):
            issues.append("no concrete tool result")
        return {"role": self.role, "verdict": verdict, "issues": issues,
                "confidence": 0.7 if verdict == "approve" else 0.5}


class MemorySteward(Specialist):
    role = "memory_steward"

    async def act(self, goal: str, context: dict) -> dict:
        research = context.get("researcher", {})
        should_store = research.get("confidence", 0) >= 0.5
        return {"role": self.role, "should_store": should_store,
                "candidate": research.get("finding", "")[:200],
                "provenance": {"why": "multi-agent-research", "source": "researcher"},
                "confidence": research.get("confidence", 0.0)}
