"""Approval 2.0 multi-stage routing + eval runs/scores with history."""

VERSION = 14
NAME = "approvals2_evals"


def UP(conn):
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS approval_stages (
            id TEXT PRIMARY KEY,
            approval_id TEXT NOT NULL,
            stage_index INTEGER NOT NULL DEFAULT 0,
            approver_role TEXT NOT NULL DEFAULT 'operator',
            status TEXT NOT NULL DEFAULT 'pending',
            decided_by TEXT NOT NULL DEFAULT '',
            justification TEXT NOT NULL DEFAULT '',
            sla_deadline TEXT NOT NULL DEFAULT '',
            escalated INTEGER NOT NULL DEFAULT 0,
            updated_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_appr_stages_appr ON approval_stages(approval_id);
        CREATE TABLE IF NOT EXISTS eval_runs (
            id TEXT PRIMARY KEY,
            created_at TEXT NOT NULL,
            suite TEXT NOT NULL,
            mode TEXT NOT NULL DEFAULT 'offline',
            score REAL NOT NULL DEFAULT 0,
            passed INTEGER NOT NULL DEFAULT 0,
            threshold REAL NOT NULL DEFAULT 0.85,
            baseline REAL NOT NULL DEFAULT 0,
            regression REAL NOT NULL DEFAULT 0,
            detail TEXT NOT NULL DEFAULT '{}'
        );
        CREATE INDEX IF NOT EXISTS idx_eval_runs_suite ON eval_runs(suite);
        CREATE TABLE IF NOT EXISTS eval_scores (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            run_id TEXT NOT NULL,
            scenario TEXT NOT NULL,
            score REAL NOT NULL DEFAULT 0,
            passed INTEGER NOT NULL DEFAULT 0,
            detail TEXT NOT NULL DEFAULT ''
        );
        CREATE INDEX IF NOT EXISTS idx_eval_scores_run ON eval_scores(run_id);
        """
    )


def DOWN(conn):
    conn.executescript(
        """
        DROP INDEX IF EXISTS idx_eval_scores_run;
        DROP TABLE IF EXISTS eval_scores;
        DROP INDEX IF EXISTS idx_eval_runs_suite;
        DROP TABLE IF EXISTS eval_runs;
        DROP INDEX IF EXISTS idx_appr_stages_appr;
        DROP TABLE IF EXISTS approval_stages;
        """
    )
