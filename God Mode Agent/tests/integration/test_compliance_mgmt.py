"""Integration tests for the compliance management console (/compliance/* ext).

Offline: uses the seeded admin and the isolated tmp SQLite from conftest.
"""

from __future__ import annotations

import pytest


@pytest.fixture()
def cc_client(app, admin_tokens):
    """Admin client with the compliance-ext routes registered on the app."""
    from fastapi.testclient import TestClient

    from jarvis_api.compliance_ext_routes import register_compliance_ext

    register_compliance_ext(app)
    with TestClient(app) as c:
        c.headers.update({"Authorization": f"Bearer {admin_tokens['access_token']}"})
        yield c


def test_meta_lists_regions_and_mode_descriptions(cc_client):
    r = cc_client.get("/compliance/meta")
    assert r.status_code == 200, r.text
    data = r.json()["data"]
    assert "local" in data["regions"] and "global" in data["regions"]
    keys = {m["key"] for m in data["modes_detail"]}
    assert {"compliance_mode", "export_controls"}.issubset(keys)
    assert all(m["description"] for m in data["modes_detail"])


def test_mode_change_requires_reason(cc_client):
    ok = cc_client.post("/compliance/mode", json={"key": "export_controls", "value": True, "reason": "audit prep"})
    assert ok.status_code == 200, ok.text
    assert ok.json()["data"]["modes"]["export_controls"] is True
    bad = cc_client.post("/compliance/mode", json={"key": "export_controls", "value": False, "reason": ""})
    assert bad.status_code == 400, bad.text


def test_retention_request_approve_applies(cc_client):
    req = cc_client.post("/compliance/retention/request", json={
        "op": "upsert", "data_class": "test_docs", "retention_days": 222,
        "region": "local", "deletion_window_days": 14, "reason": "policy setup"})
    assert req.status_code == 200, req.text
    req_id = req.json()["data"]["id"]
    assert req.json()["data"]["status"] == "pending"

    dec = cc_client.post(f"/compliance/retention/requests/{req_id}/decide",
                         json={"decision": "approve", "reason": "ok"})
    assert dec.status_code == 200, dec.text
    policies = {p["data_class"]: p for p in dec.json()["data"]["retention"]}
    assert policies["test_docs"]["retention_days"] == 222
    assert policies["test_docs"]["region"] == "local"


def test_retention_upsert_requires_days(cc_client):
    bad = cc_client.post("/compliance/retention/request",
                         json={"op": "upsert", "data_class": "x", "reason": "missing days"})
    assert bad.status_code == 400, bad.text


def test_retention_delete_request_approve_removes(cc_client):
    cc_client.post("/compliance/retention/request", json={
        "op": "upsert", "data_class": "temp_class", "retention_days": 10,
        "region": "global", "deletion_window_days": 1, "reason": "add"})
    # approve the add
    pend = cc_client.get("/compliance/retention/requests", params={"status": "pending"}).json()["data"]["requests"]
    add_id = next(r["id"] for r in pend if r["data_class"] == "temp_class" and r["op"] == "upsert")
    cc_client.post(f"/compliance/retention/requests/{add_id}/decide", json={"decision": "approve"})
    # request + approve a delete
    d = cc_client.post("/compliance/retention/request",
                       json={"op": "delete", "data_class": "temp_class", "reason": "remove"})
    del_id = d.json()["data"]["id"]
    res = cc_client.post(f"/compliance/retention/requests/{del_id}/decide", json={"decision": "approve"})
    classes = {p["data_class"] for p in res.json()["data"]["retention"]}
    assert "temp_class" not in classes


def test_evidence_export_returns_downloadable_content(cc_client):
    # generate an auditable action first
    cc_client.post("/compliance/mode", json={"key": "audit_strict_mode", "value": True, "reason": "seed audit"})
    r = cc_client.post("/compliance/evidence/export", params={"fmt": "json", "scope": "all"})
    assert r.status_code == 200, r.text
    data = r.json()["data"]
    assert data["record_count"] >= 1
    assert data["content"] and data["filename"].endswith(".json")
    # re-download the same export
    again = cc_client.get(f"/compliance/evidence/exports/{data['id']}/download")
    assert again.status_code == 200, again.text
    assert again.json()["data"]["content"] == data["content"]


def test_audit_summary_reports_details(cc_client):
    mode = cc_client.post("/compliance/mode",
                          json={"key": "compliance_mode", "value": True, "reason": "seed audit category"})
    assert mode.status_code == 200, mode.text
    r = cc_client.get("/compliance/audit/summary")
    assert r.status_code == 200, r.text
    data = r.json()["data"]
    assert data["ok"] is True
    assert "compliance" in data["by_category"]
    assert data["first_ts"] and data["last_ts"]
