"""Integration tests for the Evals v2 platform (/evals/v2/* ext).

Offline: deterministic scenario suites, isolated tmp SQLite from conftest.
"""

from __future__ import annotations

import pytest


@pytest.fixture()
def ev_client(app, admin_tokens):
    from fastapi.testclient import TestClient

    from jarvis_api.evals_v2_routes import register_evals_v2

    register_evals_v2(app)
    with TestClient(app) as c:
        c.headers.update({"Authorization": f"Bearer {admin_tokens['access_token']}"})
        yield c


def test_suites_meta_has_investigate_links(ev_client):
    r = ev_client.get("/evals/v2/suites")
    assert r.status_code == 200, r.text
    suites = r.json()["data"]["suites"]
    names = {s["suite"] for s in suites}
    assert {"injection_resilience", "citation_fidelity"}.issubset(names)
    assert all(s["investigate_test"] and s["investigate_module"] for s in suites)


def test_run_persists_reproducibility_metadata(ev_client):
    r = ev_client.post("/evals/v2/run", json={"branch": "feat/x", "commit_sha": "abc123", "env_profile": "local"})
    assert r.status_code == 200, r.text
    run = r.json()["data"]
    assert run["branch"] == "feat/x" and run["commit_sha"] == "abc123"
    assert run["env_profile"] == "local"
    assert run["suite_count"] >= 5
    assert isinstance(run["gate_explanation"], list) and run["gate_explanation"]
    assert run["resolved_models"] and "provider" in run["provider_meta"]


def test_run_and_drill_into_cases(ev_client):
    run = ev_client.post("/evals/v2/run", json={}).json()["data"]
    suite = run["suites"][0]["suite_name"]
    cases = ev_client.get(f"/evals/v2/runs/{run['id']}/suites/{suite}/cases").json()["data"]["cases"]
    assert cases and all("case_name" in c and "investigate_file_path" in c for c in cases)


def test_gate_policy_hard_fail_blocks(ev_client):
    # Force injection_resilience to an impossible threshold so the critical suite hard-fails.
    put = ev_client.put("/evals/v2/gate-policy", json={
        "block_deploy_on_fail": True,
        "per_suite_threshold": {"injection_resilience": 1.01},
        "critical_suites": ["injection_resilience"]})
    assert put.status_code == 200, put.text
    run = ev_client.post("/evals/v2/run", json={}).json()["data"]
    assert run["overall_gate_pass"] is False
    assert run["gate_blocked"] is True
    assert any("injection_resilience" in reason for reason in run["gate_explanation"])


def test_runs_filter_below_score(ev_client):
    ev_client.put("/evals/v2/gate-policy", json={"per_suite_threshold": {}, "critical_suites": []})
    ev_client.post("/evals/v2/run", json={})
    all_runs = ev_client.get("/evals/v2/runs", params={"limit": 50}).json()["data"]
    assert all_runs["total"] >= 1
    below = ev_client.get("/evals/v2/runs", params={"below_score": 0.0}).json()["data"]
    assert below["total"] == 0  # nothing scores below 0


def test_rerun_creates_new_run_same_metadata(ev_client):
    run = ev_client.post("/evals/v2/run", json={"branch": "repro", "commit_sha": "deadbee"}).json()["data"]
    again = ev_client.post(f"/evals/v2/runs/{run['id']}/rerun").json()["data"]
    assert again["id"] != run["id"]
    assert again["branch"] == "repro" and again["commit_sha"] == "deadbee"


def test_gate_policy_requires_admin(app, admin_tokens):
    from fastapi.testclient import TestClient

    from jarvis_api.evals_v2_routes import register_evals_v2

    register_evals_v2(app)
    # readonly principal via a freshly registered role account
    admin = admin_tokens["access_token"]
    with TestClient(app) as c:
        c.post("/auth/register", json={"username": "ev_ro", "password": "ro-pass-123", "role": "readonly"},
               headers={"Authorization": f"Bearer {admin}"})
        tok = c.post("/auth/login", json={"username": "ev_ro", "password": "ro-pass-123"}).json()["data"]["access_token"]
        r = c.put("/evals/v2/gate-policy", json={"block_deploy_on_fail": False},
                  headers={"Authorization": f"Bearer {tok}"})
        assert r.status_code in (401, 403), r.text
