"""Evals v2: run/suite/case persistence, gate policy, datasets."""

VERSION = 15
NAME = "evals_v2"


def UP(conn):
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS eval_v2_runs (
            id TEXT PRIMARY KEY,
            created_at TEXT NOT NULL,
            started_at TEXT NOT NULL DEFAULT '',
            finished_at TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT 'completed',
            commit_sha TEXT NOT NULL DEFAULT '',
            branch TEXT NOT NULL DEFAULT '',
            app_version TEXT NOT NULL DEFAULT '',
            env_profile TEXT NOT NULL DEFAULT 'local',
            dataset_version TEXT NOT NULL DEFAULT '',
            triggered_by TEXT NOT NULL DEFAULT '',
            provider_meta TEXT NOT NULL DEFAULT '{}',
            resolved_models TEXT NOT NULL DEFAULT '{}',
            overall_score REAL NOT NULL DEFAULT 0,
            overall_gate_pass INTEGER NOT NULL DEFAULT 0,
            gate_blocked INTEGER NOT NULL DEFAULT 0,
            gate_explanation TEXT NOT NULL DEFAULT '[]',
            duration_ms REAL NOT NULL DEFAULT 0,
            suite_count INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX IF NOT EXISTS idx_eval_v2_runs_created ON eval_v2_runs(created_at);
        CREATE TABLE IF NOT EXISTS eval_v2_suite_results (
            id TEXT PRIMARY KEY,
            run_id TEXT NOT NULL,
            suite_name TEXT NOT NULL,
            score REAL NOT NULL DEFAULT 0,
            threshold REAL NOT NULL DEFAULT 0.85,
            passed INTEGER NOT NULL DEFAULT 0,
            weight REAL NOT NULL DEFAULT 1,
            critical INTEGER NOT NULL DEFAULT 0,
            duration_ms REAL NOT NULL DEFAULT 0,
            token_input INTEGER NOT NULL DEFAULT 0,
            token_output INTEGER NOT NULL DEFAULT 0,
            token_total INTEGER NOT NULL DEFAULT 0,
            estimated_cost_usd REAL NOT NULL DEFAULT 0,
            sample_size INTEGER NOT NULL DEFAULT 0,
            stddev REAL NOT NULL DEFAULT 0,
            failure_count INTEGER NOT NULL DEFAULT 0,
            scope TEXT NOT NULL DEFAULT '',
            investigate_test TEXT NOT NULL DEFAULT '',
            investigate_module TEXT NOT NULL DEFAULT ''
        );
        CREATE INDEX IF NOT EXISTS idx_eval_v2_suite_run ON eval_v2_suite_results(run_id);
        CREATE INDEX IF NOT EXISTS idx_eval_v2_suite_name ON eval_v2_suite_results(suite_name);
        CREATE TABLE IF NOT EXISTS eval_v2_case_results (
            id TEXT PRIMARY KEY,
            suite_result_id TEXT NOT NULL,
            run_id TEXT NOT NULL,
            suite_name TEXT NOT NULL,
            case_id TEXT NOT NULL,
            case_name TEXT NOT NULL,
            passed INTEGER NOT NULL DEFAULT 0,
            score REAL NOT NULL DEFAULT 0,
            expected_summary TEXT NOT NULL DEFAULT '',
            actual_summary TEXT NOT NULL DEFAULT '',
            failure_reason TEXT NOT NULL DEFAULT '',
            investigate_file_path TEXT NOT NULL DEFAULT '',
            investigate_module TEXT NOT NULL DEFAULT ''
        );
        CREATE INDEX IF NOT EXISTS idx_eval_v2_case_run ON eval_v2_case_results(run_id);
        CREATE INDEX IF NOT EXISTS idx_eval_v2_case_suite_result ON eval_v2_case_results(suite_result_id);
        CREATE TABLE IF NOT EXISTS eval_gate_policy (
            id TEXT PRIMARY KEY,
            block_deploy_on_fail INTEGER NOT NULL DEFAULT 1,
            global_threshold REAL NOT NULL DEFAULT 0.85,
            per_suite_threshold TEXT NOT NULL DEFAULT '{}',
            per_suite_weight TEXT NOT NULL DEFAULT '{}',
            critical_suites TEXT NOT NULL DEFAULT '[]',
            updated_at TEXT NOT NULL DEFAULT '',
            updated_by TEXT NOT NULL DEFAULT 'system'
        );
        CREATE TABLE IF NOT EXISTS eval_datasets (
            name TEXT NOT NULL,
            version TEXT NOT NULL,
            checksum TEXT NOT NULL DEFAULT '',
            changelog TEXT NOT NULL DEFAULT '',
            active INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY (name, version)
        );
        """
    )


def DOWN(conn):
    conn.executescript(
        """
        DROP TABLE IF EXISTS eval_datasets;
        DROP TABLE IF EXISTS eval_gate_policy;
        DROP INDEX IF EXISTS idx_eval_v2_case_suite_result;
        DROP INDEX IF EXISTS idx_eval_v2_case_run;
        DROP TABLE IF EXISTS eval_v2_case_results;
        DROP INDEX IF EXISTS idx_eval_v2_suite_name;
        DROP INDEX IF EXISTS idx_eval_v2_suite_run;
        DROP TABLE IF EXISTS eval_v2_suite_results;
        DROP INDEX IF EXISTS idx_eval_v2_runs_created;
        DROP TABLE IF EXISTS eval_v2_runs;
        """
    )
