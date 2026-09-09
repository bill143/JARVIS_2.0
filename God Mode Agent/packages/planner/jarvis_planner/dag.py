"""Task DAG + deterministic goal decomposition.

The deterministic planner turns a goal string into an ordered task graph with
explicit dependencies. It recognizes tool intents (compute/search/write/read)
and always ends with a synthesis step depending on the prior steps — giving
reproducible plans for tests while remaining useful for real goals.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field


@dataclass
class TaskNode:
    id: str
    name: str
    action: str = "noop"
    arguments: dict = field(default_factory=dict)
    depends_on: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {"id": self.id, "name": self.name, "action": self.action,
                "arguments": self.arguments, "depends_on": self.depends_on}


class TaskGraph:
    def __init__(self, nodes: list[TaskNode] | None = None):
        self.nodes: list[TaskNode] = nodes or []

    def add(self, node: TaskNode) -> None:
        self.nodes.append(node)

    def validate_acyclic(self) -> bool:
        """Kahn's algorithm — returns True if the graph is a DAG."""
        ids = {n.id for n in self.nodes}
        indeg = {n.id: 0 for n in self.nodes}
        adj: dict[str, list[str]] = {n.id: [] for n in self.nodes}
        for n in self.nodes:
            for dep in n.depends_on:
                if dep in ids:
                    adj[dep].append(n.id)
                    indeg[n.id] += 1
        queue = [i for i, d in indeg.items() if d == 0]
        seen = 0
        while queue:
            cur = queue.pop()
            seen += 1
            for nxt in adj[cur]:
                indeg[nxt] -= 1
                if indeg[nxt] == 0:
                    queue.append(nxt)
        return seen == len(self.nodes)

    def topological_order(self) -> list[TaskNode]:
        by_id = {n.id: n for n in self.nodes}
        indeg = {n.id: 0 for n in self.nodes}
        adj: dict[str, list[str]] = {n.id: [] for n in self.nodes}
        for n in self.nodes:
            for dep in n.depends_on:
                if dep in by_id:
                    adj[dep].append(n.id)
                    indeg[n.id] += 1
        # stable order by original index for determinism
        order_index = {n.id: i for i, n in enumerate(self.nodes)}
        queue = sorted([i for i, d in indeg.items() if d == 0], key=lambda x: order_index[x])
        out: list[TaskNode] = []
        while queue:
            cur = queue.pop(0)
            out.append(by_id[cur])
            for nxt in sorted(adj[cur], key=lambda x: order_index[x]):
                indeg[nxt] -= 1
                if indeg[nxt] == 0:
                    queue.append(nxt)
            queue.sort(key=lambda x: order_index[x])
        return out

    def as_dict(self) -> dict:
        return {"nodes": [n.as_dict() for n in self.nodes], "acyclic": self.validate_acyclic()}


_COMPUTE_RE = re.compile(r"(?i)\b(compute|calculate|evaluate|sum|multiply|add)\b\s*(.*)")
_SEARCH_RE = re.compile(r"(?i)\b(search|research|find|look up|lookup|investigate)\b\s*(.*)")
_WRITE_RE = re.compile(r"(?i)\b(write|save|create file|record)\b\s*(.*)")


def decompose_goal(goal: str, max_steps: int = 20) -> TaskGraph:
    """Deterministically decompose a goal into a task DAG."""
    graph = TaskGraph()
    steps: list[TaskNode] = []
    # Split the goal into clauses on 'then'/';'/'and then' for multi-step goals.
    clauses = [c.strip() for c in re.split(r"(?i)\bthen\b|;|\band then\b", goal) if c.strip()]
    if not clauses:
        clauses = [goal]

    for i, clause in enumerate(clauses[: max_steps - 1]):
        node_id = f"s{i + 1}"
        depends = [f"s{i}"] if i > 0 else []
        if _COMPUTE_RE.search(clause):
            expr = _COMPUTE_RE.search(clause).group(2).strip() or clause
            steps.append(TaskNode(node_id, f"compute: {clause[:40]}", "tool:python_exec",
                                  {"code": f"print({_safe_expr(expr)})"}, depends))
        elif _SEARCH_RE.search(clause):
            q = _SEARCH_RE.search(clause).group(2).strip() or clause
            steps.append(TaskNode(node_id, f"research: {clause[:40]}", "tool:web_search",
                                  {"query": q}, depends))
        elif _WRITE_RE.search(clause):
            steps.append(TaskNode(node_id, f"write: {clause[:40]}", "tool:file_write",
                                  {"path": f"plan_output_{i + 1}.txt", "content": clause}, depends))
        else:
            steps.append(TaskNode(node_id, f"reason: {clause[:40]}", "reason", {"prompt": clause}, depends))

    # Final synthesis step depends on all prior steps.
    synth = TaskNode(f"s{len(steps) + 1}", "synthesize final answer", "synthesize",
                     {"goal": goal}, [n.id for n in steps])
    for n in steps:
        graph.add(n)
    graph.add(synth)
    return graph


def _safe_expr(expr: str) -> str:
    """Extract a simple arithmetic expression, else echo a quoted string."""
    m = re.search(r"[-+*/(). 0-9]+", expr)
    if m and any(op in m.group(0) for op in "+-*/"):
        return m.group(0).strip()
    return repr(expr)
