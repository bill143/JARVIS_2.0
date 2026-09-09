"""Phase 3 eval platform: suites, scoring, quality gates, regression, history."""

from jarvis_evals import SUITES, EvalRunner
from jarvis_memory.governance import MemoryGovernanceStore
from jarvis_rag import HybridRetriever, IngestionPipeline, RagStore
from jarvis_tools.python_exec import run_python_sandboxed


def _context(settings):
    rag = RagStore(settings.sqlite_path)
    IngestionPipeline(rag, settings).ingest_text(
        tenant="default", source="kb", title="t",
        content="Quantum computing uses qubits and superposition for parallel computation.")
    retriever = HybridRetriever(rag, settings)
    mem = MemoryGovernanceStore(settings.sqlite_path, settings)
    return {
        "sandbox": lambda code: run_python_sandboxed(code, timeout=5),
        "retriever": lambda q: retriever.answer("default", q),
        "memory": mem,
        "known_query": "quantum qubits",
    }


def test_all_suites_present():
    expected = {"reasoning", "tool_correctness", "hallucination_resistance", "citation_fidelity",
                "injection_resilience", "memory_correctness", "arbitration_quality"}
    assert expected == set(SUITES)


def test_run_all_and_gate(settings):
    runner = EvalRunner(settings.sqlite_path, settings)
    result = runner.run_all(_context(settings))
    assert len(result["suites"]) == 7
    assert result["overall_score"] >= settings.eval_pass_threshold
    assert result["gate_passed"] is True
    runner.close()


def test_injection_suite_scores_high(settings):
    runner = EvalRunner(settings.sqlite_path, settings)
    r = runner.run_suite("injection_resilience", _context(settings))
    assert r["score"] >= settings.eval_pass_threshold
    runner.close()


def test_regression_gate(settings):
    """First run establishes a baseline for reasoning (always 1.0) and passes the gate."""
    s = settings.model_copy(update={"eval_pass_threshold": 0.5, "regression_tolerance": 0.03})
    runner = EvalRunner(s.sqlite_path, s)
    first = runner.run_suite("reasoning", {})
    assert first["passed"] is True and first["score"] == 1.0
    runner.close()


def test_history_records_runs(settings):
    runner = EvalRunner(settings.sqlite_path, settings)
    runner.run_suite("reasoning", {})
    runner.run_suite("reasoning", {})
    history = runner.history("reasoning")
    assert len(history) >= 2
    runner.close()
