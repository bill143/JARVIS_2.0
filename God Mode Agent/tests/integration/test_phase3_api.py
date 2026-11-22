"""Phase 3 API integration: workflows, agents, RAG, memory gov, routing/cost, evals."""


# ---------- workflows ----------

def test_workflow_create_start_status(client):
    created = client.post("/workflows", json={"goal": "compute 2+2 then compute 3+3", "mode": "plan-and-execute"})
    assert created.status_code == 200
    wf = created.json()["data"]
    assert wf["steps"] and all(s["status"] == "pending" for s in wf["steps"])

    started = client.post(f"/workflows/{wf['id']}/start")
    assert started.status_code == 200
    result = started.json()["data"]
    assert result["status"] == "completed"
    assert result["result"]["steps_completed"] >= 1

    status = client.get(f"/workflows/{wf['id']}")
    assert status.status_code == 200
    assert status.json()["data"]["checkpoints"]


def test_workflow_abort(client):
    wf = client.post("/workflows", json={"goal": "compute 1+1", "mode": "plan-and-execute"}).json()["data"]
    resp = client.post(f"/workflows/{wf['id']}/abort")
    assert resp.status_code == 200 and resp.json()["data"]["status"] == "aborted"


def test_workflow_human_approval_gated_resume(client):
    wf = client.post("/workflows", json={"goal": "write a summary file", "mode": "human-approval-gated"}).json()["data"]
    started = client.post(f"/workflows/{wf['id']}/start").json()["data"]
    assert started["status"] == "awaiting_approval"
    resumed = client.post(f"/workflows/{wf['id']}/resume").json()["data"]
    assert resumed["status"] == "completed"


# ---------- multi-agent ----------

def test_agents_run_and_introspect(client):
    run = client.post("/agents/run", json={"goal": "investigate quantum computing"})
    assert run.status_code == 200
    result = run.json()["data"]
    assert result["session_id"]
    assert result["decision"] in ("approved", "needs_revision", "single-agent-fallback")

    session = client.get(f"/agents/sessions/{result['session_id']}")
    assert session.status_code == 200
    senders = {m["sender"] for m in session.json()["data"]["messages"]}
    assert "coordinator" in senders and "verifier" in senders


# ---------- RAG ----------

def test_rag_ingest_query_citations(operator_client):
    ing = operator_client.post("/rag/ingest", json={
        "source": "kb1", "title": "Quantum",
        "content": "Quantum computing uses qubits and superposition for parallel computation.",
        "sensitivity": "internal", "strategy": "semantic"})
    assert ing.status_code == 200 and ing.json()["data"]["chunks"] >= 1

    q = operator_client.post("/rag/query", json={"query": "what are qubits in quantum computing"})
    assert q.status_code == 200
    data = q.json()["data"]
    assert data["citations"]
    assert data["confidence"] >= 0

    cites = operator_client.get("/rag/citations", params={"q": "qubits superposition"})
    assert cites.status_code == 200 and cites.json()["data"]["hybrid"] is True


def test_rag_user_cannot_ingest(client, readonly_client):
    # readonly cannot ingest (operator+ required)
    assert readonly_client.post("/rag/ingest", json={"source": "x", "content": "hello world text"}).status_code == 403


# ---------- memory governance ----------

def test_memory_governance_lifecycle(client):
    add = client.post("/memory/items", json={"text": "remember the launch date is may 1", "confidence": 0.9})
    assert add.status_code == 200
    item_id = add.json()["data"]["id"]

    lst = client.get("/memory/items")
    assert any(i["id"] == item_id for i in lst.json()["data"]["items"])

    pinned = client.post(f"/memory/items/{item_id}/pin", params={"pinned": True})
    assert pinned.json()["data"]["pinned"] is True

    edit = client.post(f"/memory/items/{item_id}/edit", json={"text": "launch date is june 1", "confidence": 0.95})
    assert "june" in edit.json()["data"]["text"]

    export = client.get("/memory/export")
    assert export.json()["data"]["count"] >= 1

    forget = client.post(f"/memory/items/{item_id}/forget")
    assert forget.json()["data"]["forgotten"] is True


def test_memory_conflict_surfaced(client):
    client.post("/memory/items", json={"text": "the server room is on floor 3"})
    client.post("/memory/items", json={"text": "the server room is not on floor 3"})
    conflicts = client.get("/memory/conflicts").json()["data"]["conflicts"]
    assert conflicts


# ---------- routing / cost ----------

def test_routing_plan_and_cost(client):
    plan = client.post("/routing/plan", json={"message": "please reason step by step", "risk": "low"})
    assert plan.status_code == 200
    assert plan.json()["data"]["task_type"] == "reasoning"

    # generate some usage via chat, then check the cost dashboard
    client.post("/chat", json={"message": "compute 2+2", "session_id": "cost"})
    usage = client.get("/cost/usage")
    assert usage.status_code == 200
    assert "by_model" in usage.json()["data"]["usage"]


def test_budget_set_and_status(client):
    set_resp = client.post("/cost/budget", json={"daily_usd": 5.0})
    assert set_resp.status_code == 200 and set_resp.json()["data"]["daily_limit_usd"] == 5.0


def test_slo_endpoint(client):
    client.post("/chat", json={"message": "compute 2+2", "session_id": "slo"})
    slo = client.get("/observability/slo")
    assert slo.status_code == 200
    data = slo.json()["data"]
    assert "latency" in data and "tool_success_rate" in data and "cost" in data


# ---------- evals ----------

def test_evals_run_and_history(operator_client):
    run = operator_client.post("/evals/run", json={"suite": "all", "mode": "offline"})
    assert run.status_code == 200
    result = run.json()["data"]
    assert "overall_score" in result and "gate_passed" in result
    assert len(result["suites"]) == 7

    history = operator_client.get("/evals/history")
    assert history.status_code == 200 and history.json()["data"]["history"]


def test_readonly_cannot_run_evals(readonly_client):
    assert readonly_client.post("/evals/run", json={"suite": "reasoning"}).status_code == 403
