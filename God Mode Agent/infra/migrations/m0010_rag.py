"""Advanced RAG: documents + chunks with versioning, metadata, freshness."""

VERSION = 10
NAME = "rag"


def UP(conn):
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS rag_documents (
            id TEXT PRIMARY KEY,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            tenant TEXT NOT NULL DEFAULT 'default',
            source TEXT NOT NULL DEFAULT '',
            title TEXT NOT NULL DEFAULT '',
            owner TEXT NOT NULL DEFAULT '',
            sensitivity TEXT NOT NULL DEFAULT 'internal',
            version INTEGER NOT NULL DEFAULT 1,
            content_hash TEXT NOT NULL DEFAULT '',
            timestamp TEXT NOT NULL DEFAULT ''
        );
        CREATE INDEX IF NOT EXISTS idx_rag_docs_tenant ON rag_documents(tenant);
        CREATE INDEX IF NOT EXISTS idx_rag_docs_hash ON rag_documents(content_hash);
        CREATE TABLE IF NOT EXISTS rag_chunks (
            id TEXT PRIMARY KEY,
            document_id TEXT NOT NULL,
            tenant TEXT NOT NULL DEFAULT 'default',
            idx INTEGER NOT NULL DEFAULT 0,
            text TEXT NOT NULL,
            strategy TEXT NOT NULL DEFAULT 'fixed',
            embedding TEXT NOT NULL DEFAULT '[]',
            terms TEXT NOT NULL DEFAULT '{}',
            timestamp TEXT NOT NULL DEFAULT '',
            source TEXT NOT NULL DEFAULT ''
        );
        CREATE INDEX IF NOT EXISTS idx_rag_chunks_doc ON rag_chunks(document_id);
        CREATE INDEX IF NOT EXISTS idx_rag_chunks_tenant ON rag_chunks(tenant);
        """
    )


def DOWN(conn):
    conn.executescript(
        """
        DROP INDEX IF EXISTS idx_rag_chunks_tenant;
        DROP INDEX IF EXISTS idx_rag_chunks_doc;
        DROP TABLE IF EXISTS rag_chunks;
        DROP INDEX IF EXISTS idx_rag_docs_hash;
        DROP INDEX IF EXISTS idx_rag_docs_tenant;
        DROP TABLE IF EXISTS rag_documents;
        """
    )
