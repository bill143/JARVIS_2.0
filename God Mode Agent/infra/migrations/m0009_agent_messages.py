"""Multi-agent message bus trace (auditable agent-to-agent communication)."""

VERSION = 9
NAME = "agent_messages"


def UP(conn):
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS agent_sessions (
            id TEXT PRIMARY KEY,
            created_at TEXT NOT NULL,
            goal TEXT NOT NULL,
            owner TEXT NOT NULL DEFAULT '',
            tenant TEXT NOT NULL DEFAULT 'default',
            status TEXT NOT NULL DEFAULT 'running',
            arbitration TEXT NOT NULL DEFAULT '',
            result TEXT NOT NULL DEFAULT ''
        );
        CREATE TABLE IF NOT EXISTS agent_messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id TEXT NOT NULL,
            ts TEXT NOT NULL,
            round INTEGER NOT NULL DEFAULT 0,
            sender TEXT NOT NULL,
            recipient TEXT NOT NULL DEFAULT 'all',
            role TEXT NOT NULL DEFAULT '',
            content TEXT NOT NULL DEFAULT '',
            meta TEXT NOT NULL DEFAULT '{}'
        );
        CREATE INDEX IF NOT EXISTS idx_agent_msgs_session ON agent_messages(session_id);
        """
    )


def DOWN(conn):
    conn.executescript(
        """
        DROP INDEX IF EXISTS idx_agent_msgs_session;
        DROP TABLE IF EXISTS agent_messages;
        DROP TABLE IF EXISTS agent_sessions;
        """
    )
