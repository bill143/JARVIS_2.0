"""Observability unit tests: metrics rendering, hash-chained audit tamper-evidence."""

from jarvis_observability.audit_chain import AuditChain
from jarvis_observability.metrics import Metrics, estimate_cost


def test_metrics_prometheus_format():
    m = Metrics()
    m.counter("jarvis_test_total", 2, labels={"kind": "a"}, help="test counter")
    m.gauge("jarvis_test_gauge", 5)
    m.observe("jarvis_test_seconds", 0.2)
    text = m.render()
    assert "# TYPE jarvis_test_total counter" in text
    assert 'jarvis_test_total{kind="a"} 2' in text
    assert "jarvis_test_seconds_bucket" in text
    assert "jarvis_test_seconds_count" in text


def test_cost_estimate():
    assert estimate_cost("gpt-4o", 1000) == 0.005
    assert estimate_cost("mock-1", 1000) == 0.0


def test_audit_chain_append_and_verify(settings):
    chain = AuditChain(settings.sqlite_path, enable_hash_chain=True)
    chain.record("auth", "login", actor="alice", detail={"ip": "127.0.0.1"})
    chain.record("policy", "tool:web_search", actor="alice", detail={"decision": "allow"})
    chain.record("tool", "executed:web_search", actor="alice")
    v = chain.verify()
    assert v["ok"] is True and v["entries"] == 3
    chain.close()


def test_audit_chain_detects_tampering(settings):
    chain = AuditChain(settings.sqlite_path, enable_hash_chain=True)
    chain.record("auth", "login", actor="alice")
    chain.record("auth", "login", actor="bob")
    # Tamper with a row directly, bypassing record()
    chain.conn.execute("UPDATE audit_chain SET action = 'tampered' WHERE id = 1")
    chain.conn.commit()
    v = chain.verify()
    assert v["ok"] is False and v["broken_at"] == 1
    chain.close()


def test_audit_secret_masking(settings):
    chain = AuditChain(settings.sqlite_path)
    chain.record("auth", "token_issue", actor="alice", detail={"authorization": "Bearer sk-supersecretkey12345"})
    entry = chain.query(category="auth")[0]
    assert "supersecretkey" not in str(entry["detail"])
    chain.close()
