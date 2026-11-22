"""Planner workflows + steps (DAG persistence with replay/resume support)."""

VERSION = 8
NAME = "workflows"


def UP(conn):
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS workflows (
            id TEXT PRIMARY KEY,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            goal TEXT NOT NULL,
            mode TEXT NOT NULL DEFAULT 'plan-and-execute',
            status TEXT NOT NULL DEFAULT 'pending',
            owner TEXT NOT NULL DEFAULT '',
            tenant TEXT NOT NULL DEFAULT 'default',
            cursor INTEGER NOT NULL DEFAULT 0,
            result TEXT NOT NULL DEFAULT '',
            error TEXT NOT NULL DEFAULT ''
        );
        CREATE TABLE IF NOT EXISTS workflow_steps (
            id TEXT PRIMARY KEY,
            workflow_id TEXT NOT NULL,
            idx INTEGER NOT NULL,
            name TEXT NOT NULL,
            action TEXT NOT NULL DEFAULT 'noop',
            arguments TEXT NOT NULL DEFAULT '{}',
            depends_on TEXT NOT NULL DEFAULT '[]',
            status TEXT NOT NULL DEFAULT 'pending',
            attempts INTEGER NOT NULL DEFAULT 0,
            max_attempts INTEGER NOT NULL DEFAULT 2,
            result TEXT NOT NULL DEFAULT '',
            error TEXT NOT NULL DEFAULT '',
            updated_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_wf_steps_wf ON workflow_steps(workflow_id);
        CREATE TABLE IF NOT EXISTS workflow_checkpoints (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            workflow_id TEXT NOT NULL,
            ts TEXT NOT NULL,
            cursor INTEGER NOT NULL,
            snapshot TEXT NOT NULL DEFAULT '{}'
        );
        CREATE INDEX IF NOT EXISTS idx_wf_ckpt_wf ON workflow_checkpoints(workflow_id);
        """
    )


def DOWN(conn):
    conn.executescript(
        """
        DROP INDEX IF EXISTS idx_wf_ckpt_wf;
        DROP TABLE IF EXISTS workflow_checkpoints;
        DROP INDEX IF EXISTS idx_wf_steps_wf;
        DROP TABLE IF EXISTS workflow_steps;
        DROP TABLE IF EXISTS workflows;
        """
    )
