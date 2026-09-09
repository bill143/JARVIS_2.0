"""Eval runner: executes suites, persists runs/scores, enforces quality gates.

A run passes when its score >= threshold AND regression vs the last baseline
does not exceed regression_tolerance. History supports trend graphs.
"""

from __future__ import annotations

import json
import sqlite3
import threading
import uuid
from datetime import UTC, datetime
from pathlib import Path

from jarvis_evals.scenarios import SUITES


class EvalRunner:
    def __init__(self, db_path: Path, settings):
        self.db_path = Path(db_path)
        self.settings = settings
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=5000")
        self._ensure()

    def _ensure(self) -> None:
        with self._lock:
            self.conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS eval_runs (
                    id TEXT PRIMARY KEY, created_at TEXT NOT NULL, suite TEXT NOT NULL, mode TEXT NOT NULL DEFAULT 'offline',
                    score REAL NOT NULL DEFAULT 0, passed INTEGER NOT NULL DEFAULT 0, threshold REAL NOT NULL DEFAULT 0.85,
                    baseline REAL NOT NULL DEFAULT 0, regression REAL NOT NULL DEFAULT 0, detail TEXT NOT NULL DEFAULT '{}'
                );
                CREATE INDEX IF NOT EXISTS idx_eval_runs_suite ON eval_runs(suite);
                CREATE TABLE IF NOT EXISTS eval_scores (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, scenario TEXT NOT NULL,
                    score REAL NOT NULL DEFAULT 0, passed INTEGER NOT NULL DEFAULT 0, detail TEXT NOT NULL DEFAULT ''
                );
                """
            )
            self.conn.commit()

    def _baseline(self, suite: str) -> float:
        with self._lock:
            row = self.conn.execute(
                "SELECT score FROM eval_runs WHERE suite = ? ORDER BY created_at DESC LIMIT 1", (suite,)).fetchone()
        return row[0] if row else 0.0

    def run_suite(self, suite: str, context: dict | None = None, mode: str = "offline") -> dict:
        if suite not in SUITES:
            raise ValueError(f"unknown suite '{suite}'")
        context = context or {}
        results = SUITES[suite](context)
        score = round(sum(s for _, s in results) / len(results), 4) if results else 0.0
        threshold = self.settings.eval_pass_threshold
        baseline = self._baseline(suite)
        regression = round(max(0.0, baseline - score), 4)
        passed = score >= threshold and regression <= self.settings.regression_tolerance

        run_id = uuid.uuid4().hex[:16]
        now = datetime.now(UTC).isoformat()
        with self._lock:
            self.conn.execute(
                "INSERT INTO eval_runs (id, created_at, suite, mode, score, passed, threshold, baseline, regression, detail) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (run_id, now, suite, mode, score, 1 if passed else 0, threshold, baseline, regression,
                 json.dumps({"scenarios": len(results)})))
            for name, sc in results:
                self.conn.execute(
                    "INSERT INTO eval_scores (run_id, scenario, score, passed) VALUES (?, ?, ?, ?)",
                    (run_id, name, sc, 1 if sc >= threshold else 0))
            self.conn.commit()
        return {"run_id": run_id, "suite": suite, "mode": mode, "score": score, "passed": passed,
                "threshold": threshold, "baseline": baseline, "regression": regression,
                "scenarios": [{"scenario": n, "score": s} for n, s in results]}

    def run_all(self, context: dict | None = None, mode: str = "offline") -> dict:
        runs = [self.run_suite(s, context, mode) for s in SUITES]
        overall = round(sum(r["score"] for r in runs) / len(runs), 4) if runs else 0.0
        gate_passed = all(r["passed"] for r in runs)
        return {"overall_score": overall, "gate_passed": gate_passed, "suites": runs,
                "required_for_release": self.settings.evals_required_for_release}

    def history(self, suite: str | None = None, limit: int = 50) -> list[dict]:
        with self._lock:
            if suite:
                rows = self.conn.execute(
                    "SELECT id, created_at, suite, score, passed, baseline, regression FROM eval_runs "
                    "WHERE suite = ? ORDER BY created_at DESC LIMIT ?", (suite, limit)).fetchall()
            else:
                rows = self.conn.execute(
                    "SELECT id, created_at, suite, score, passed, baseline, regression FROM eval_runs "
                    "ORDER BY created_at DESC LIMIT ?", (limit,)).fetchall()
        return [{"id": r[0], "created_at": r[1], "suite": r[2], "score": r[3], "passed": bool(r[4]),
                 "baseline": r[5], "regression": r[6]} for r in rows]

    def close(self) -> None:
        with self._lock:
            self.conn.close()
