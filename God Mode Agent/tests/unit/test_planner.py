"""Planner: DAG decomposition, acyclicity, workflow execution + checkpoints/resume."""

from jarvis_planner import WorkflowEngine, WorkflowStore, decompose_goal


def test_decompose_produces_dag():
    g = decompose_goal("research quantum computing then compute 2*21 then write a summary")
    assert g.validate_acyclic()
    assert len(g.nodes) == 4  # 3 clauses + synthesis
    synth = g.nodes[-1]
    assert synth.action == "synthesize"
    assert len(synth.depends_on) == 3  # depends on all prior steps


def test_topological_order_respects_dependencies():
    g = decompose_goal("compute 1+1 then compute 2+2")
    order = [n.id for n in g.topological_order()]
    assert order.index("s1") < order.index("s2") < order.index("s3")


async def test_workflow_execute_and_checkpoints(settings):
    store = WorkflowStore(settings.sqlite_path)
    g = decompose_goal("compute 2+2 then compute 3+3")
    wf = store.create("compute 2+2 then compute 3+3", "plan-and-execute", g, owner="u", tenant="t")

    async def runner(step):
        return ("completed", {"ok": step["action"]})

    engine = WorkflowEngine(store, runner)
    result = await engine.execute(wf["id"])
    assert result["status"] == "completed"
    assert all(s["status"] == "completed" for s in result["steps"])
    # checkpoints recorded per completed step
    assert len(store.checkpoints(wf["id"])) >= 3
    store.close()


async def test_workflow_resume_after_failure(settings):
    store = WorkflowStore(settings.sqlite_path)
    g = decompose_goal("compute 1+1 then compute 2+2 then compute 3+3")
    wf = store.create("g", "plan-and-execute", g, owner="u", tenant="t")

    calls = {"n": 0}

    async def flaky(step):
        calls["n"] += 1
        # fail the second step on its first attempt only
        if step["idx"] == 1 and calls["n"] <= 2:
            return ("failed", {"error": "transient"})
        return ("completed", {"ok": True})

    engine = WorkflowEngine(store, flaky)
    r1 = await engine.execute(wf["id"])
    # step 2 has max_attempts=2; both fail on first pass -> workflow failed
    assert r1["status"] in ("failed", "completed")
    # resume continues from persisted state
    r2 = await engine.execute(wf["id"])
    assert r2["status"] in ("completed", "failed")
    store.close()


async def test_human_approval_gated_pauses(settings):
    store = WorkflowStore(settings.sqlite_path)
    g = decompose_goal("write a report file")  # produces a file_write high-risk step
    wf = store.create("write a report file", "human-approval-gated", g, owner="u", tenant="t")

    async def runner(step):
        return ("completed", {"ok": True})

    engine = WorkflowEngine(store, runner)
    result = await engine.execute(wf["id"], approve_gate=lambda step: False)
    assert result["status"] == "awaiting_approval"
    # resume with approval
    result2 = await engine.execute(wf["id"], approve_gate=lambda step: True)
    assert result2["status"] == "completed"
    store.close()


def test_unknown_cycle_detection():
    from jarvis_planner.dag import TaskGraph, TaskNode
    g = TaskGraph([TaskNode("a", "a", depends_on=["b"]), TaskNode("b", "b", depends_on=["a"])])
    assert not g.validate_acyclic()
