"""Compliance suite: retention, KMS, evidence export, audit validation, modes."""

from pathlib import Path

from jarvis_compliance import ComplianceModes, EvidenceExporter, RetentionManager, get_kms
from jarvis_observability.audit_chain import AuditChain


def test_retention_policies_seeded(settings):
    rm = RetentionManager(settings.sqlite_path, settings)
    policies = {p["data_class"]: p for p in rm.list_policies()}
    assert "audit" in policies and policies["audit"]["retention_days"] >= 365
    rm.set_policy("chat", 90, region="eu", deletion_window_days=14)
    assert rm.get_policy("chat")["region"] == "eu"
    rm.close()


def test_kms_encrypt_decrypt_rotate():
    kms = get_kms("local")
    ct = kms.encrypt("top secret value", key_id="k1")
    assert ct.startswith("kms:local:")
    assert kms.decrypt(ct, key_id="k1") == "top secret value"
    # rotate -> new version; old ciphertext still decrypts under its sealed version
    kms.rotate("k1")
    ct2 = kms.encrypt("new value", key_id="k1")
    assert "v2" in ct2
    assert kms.decrypt(ct2, key_id="k1") == "new value"
    assert kms.decrypt(ct, key_id="k1") == "top secret value"


def test_evidence_export_json_and_csv(settings):
    audit = AuditChain(settings.sqlite_path, enable_hash_chain=True)
    audit.record("auth", "login", actor="alice")
    audit.record("policy", "tool:web_search", actor="alice", detail={"decision": "allow"})
    exporter = EvidenceExporter(settings.sqlite_path, audit, settings.evidence_export_path)

    j = exporter.export(requested_by="admin", fmt="json")
    assert j["record_count"] >= 2 and j["digest"]
    assert j["verification"]["ok"] is True

    c = exporter.export(requested_by="admin", fmt="csv")
    assert c["format"] == "csv"
    assert Path(c["path"]).exists()

    assert len(exporter.list_exports()) >= 2
    audit.close()
    exporter.close()


def test_audit_chain_validation_detects_tamper(settings):
    audit = AuditChain(settings.sqlite_path, enable_hash_chain=True)
    audit.record("auth", "login", actor="a")
    audit.record("auth", "login", actor="b")
    exporter = EvidenceExporter(settings.sqlite_path, audit, settings.evidence_export_path)
    assert exporter.validate_chain()["ok"] is True
    audit.conn.execute("UPDATE audit_chain SET action = 'tampered' WHERE id = 1")
    audit.conn.commit()
    assert exporter.validate_chain()["ok"] is False
    audit.close()
    exporter.close()


def test_compliance_modes_and_restricted_tools(settings):
    s = settings.model_copy(update={"compliance_mode": True})
    modes = ComplianceModes(s)
    assert modes.compliance_mode is True
    assert modes.restricted_tool_mode is True
    assert modes.tool_permitted("web_search") is True
    assert modes.tool_permitted("python_exec") is False  # restricted in compliance mode
    modes.set("restricted_tool_mode", False)
    assert modes.tool_permitted("python_exec") is True


def test_compliance_api_toggle_and_export(client):
    status = client.get("/compliance/status")
    assert status.status_code == 200 and "modes" in status.json()["data"]

    toggle = client.post("/compliance/toggle", json={"key": "audit_strict_mode", "value": True})
    assert toggle.status_code == 200 and toggle.json()["data"]["audit_strict_mode"] is True

    validate = client.get("/compliance/validate")
    assert validate.status_code == 200 and validate.json()["data"]["ok"] is True

    export = client.post("/compliance/export", params={"fmt": "json"})
    assert export.status_code == 200 and export.json()["data"]["record_count"] >= 0


def test_compliance_export_requires_admin(operator_client):
    assert operator_client.post("/compliance/export").status_code == 403
