"""Helper invoked by PowerShell scripts to run named maintenance actions.

Avoids fragile inline `python -c` quoting on Windows PowerShell. Usage:
    python scripts/_pyrun.py audit-verify
    python scripts/_pyrun.py eval-gate
    python scripts/_pyrun.py export-evidence [json|csv]
Exit code is non-zero when the action's check fails.
"""

from __future__ import annotations

import sys

from jarvis_shared.config import get_settings
from jarvis_shared.migrate import migrate_up


def audit_verify() -> int:
    from jarvis_observability.audit_chain import AuditChain

    s = get_settings()
    migrate_up(s.sqlite_path)
    chain = AuditChain(s.sqlite_path, enable_hash_chain=s.enable_audit_log_hash_chain)
    v = chain.verify()
    print(f"audit_chain ok={v['ok']} entries={v.get('entries', 0)}")
    return 0 if v["ok"] else 2


def eval_gate() -> int:
    from jarvis_evals import EvalRunner

    s = get_settings()
    migrate_up(s.sqlite_path)
    runner = EvalRunner(s.sqlite_path, s)
    result = runner.run_all({}, mode="offline")
    print(f"overall_score={result['overall_score']} gate_passed={result['gate_passed']}")
    for row in result["suites"]:
        print(f"  {row['suite']:<26} score={row['score']:.3f} passed={row['passed']}")
    return 0 if result["gate_passed"] else 1


def export_evidence(fmt: str) -> int:
    from jarvis_compliance import EvidenceExporter
    from jarvis_observability.audit_chain import AuditChain

    s = get_settings()
    migrate_up(s.sqlite_path)
    chain = AuditChain(s.sqlite_path, enable_hash_chain=s.enable_audit_log_hash_chain)
    exp = EvidenceExporter(s.sqlite_path, chain, s.evidence_export_path)
    result = exp.export(requested_by="cli-admin", fmt=fmt)
    print(f"exported id={result['id']} records={result['record_count']} digest={result['digest'][:16]}...")
    print(f"path={result['path']}")
    print(f"verification ok={result['verification']['ok']}")
    return 0


def main(argv: list[str]) -> int:
    if not argv:
        print("usage: _pyrun.py <action>", file=sys.stderr)
        return 64
    action = argv[0]
    if action == "audit-verify":
        return audit_verify()
    if action == "eval-gate":
        return eval_gate()
    if action == "export-evidence":
        return export_evidence(argv[1] if len(argv) > 1 else "json")
    print(f"unknown action '{action}'", file=sys.stderr)
    return 64


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
