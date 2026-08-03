-- ============================================================================
-- NEXUS AI — Memory Layer Schema v1.0
-- Target Project: NEXUS_ESTIMATING_AI (qulvniixtxxyppmhufha)
-- Architecture: pgvector-based semantic memory for 267-agent hierarchy
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Required Extensions
-- ----------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "vector";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

-- ----------------------------------------------------------------------------
-- 2. Schema Namespace
-- ----------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS nexus_memory;

-- ----------------------------------------------------------------------------
-- 3. Enums for the 6-Level Agent Hierarchy
-- ----------------------------------------------------------------------------
DO $$ BEGIN
  CREATE TYPE nexus_memory.agent_level AS ENUM (
    'ceo',
    'executive',
    'project_manager',
    'project_engineer',
    'superintendent',
    'worker'
  );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE nexus_memory.memory_status AS ENUM (
    'active',
    'superseded',
    'archived',
    'expired'
  );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE nexus_memory.escalation_priority AS ENUM (
    'low',
    'medium',
    'high',
    'critical'
  );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- ----------------------------------------------------------------------------
-- 4. Table: agent_profiles — One row per agent (267 total)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS nexus_memory.agent_profiles (
  agent_id        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  agent_code      TEXT UNIQUE NOT NULL,
  agent_name      TEXT NOT NULL,
  level           nexus_memory.agent_level NOT NULL,
  model           TEXT NOT NULL,
  context_window  INTEGER NOT NULL,
  reports_to      UUID REFERENCES nexus_memory.agent_profiles(agent_id),
  department      TEXT,
  specialization  TEXT,
  decision_authority TEXT,
  system_prompt   TEXT,
  tools_enabled   JSONB DEFAULT '[]'::jsonb,
  performance_score NUMERIC(3,2) DEFAULT 0.0,
  active          BOOLEAN DEFAULT true,
  created_at      TIMESTAMPTZ DEFAULT NOW(),
  updated_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_profiles_level ON nexus_memory.agent_profiles(level);
CREATE INDEX IF NOT EXISTS idx_agent_profiles_reports_to ON nexus_memory.agent_profiles(reports_to);
CREATE INDEX IF NOT EXISTS idx_agent_profiles_department ON nexus_memory.agent_profiles(department);

-- ----------------------------------------------------------------------------
-- 5. Table: agent_memories — Vector embeddings of every memory
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS nexus_memory.agent_memories (
  memory_id       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  agent_id        UUID NOT NULL REFERENCES nexus_memory.agent_profiles(agent_id) ON DELETE CASCADE,
  content         TEXT NOT NULL,
  embedding       vector(1536),
  memory_type     TEXT NOT NULL,
  importance      NUMERIC(3,2) DEFAULT 0.5,
  status          nexus_memory.memory_status DEFAULT 'active',
  source_session  UUID,
  related_project TEXT,
  tags            TEXT[] DEFAULT ARRAY[]::TEXT[],
  metadata        JSONB DEFAULT '{}'::jsonb,
  expires_at      TIMESTAMPTZ,
  created_at      TIMESTAMPTZ DEFAULT NOW(),
  updated_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_memories_agent ON nexus_memory.agent_memories(agent_id);
CREATE INDEX IF NOT EXISTS idx_memories_type ON nexus_memory.agent_memories(memory_type);
CREATE INDEX IF NOT EXISTS idx_memories_status ON nexus_memory.agent_memories(status);
CREATE INDEX IF NOT EXISTS idx_memories_tags ON nexus_memory.agent_memories USING GIN(tags);
CREATE INDEX IF NOT EXISTS idx_memories_content_trgm ON nexus_memory.agent_memories USING GIN(content gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_memories_embedding ON nexus_memory.agent_memories
  USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- ----------------------------------------------------------------------------
-- 6. Table: session_logs — Full conversation history per session
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS nexus_memory.session_logs (
  session_id      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  agent_id        UUID NOT NULL REFERENCES nexus_memory.agent_profiles(agent_id) ON DELETE CASCADE,
  started_at      TIMESTAMPTZ DEFAULT NOW(),
  ended_at        TIMESTAMPTZ,
  total_tokens    INTEGER DEFAULT 0,
  total_messages  INTEGER DEFAULT 0,
  conversation    JSONB DEFAULT '[]'::jsonb,
  summary         TEXT,
  outcome         TEXT,
  related_project TEXT,
  parent_session  UUID REFERENCES nexus_memory.session_logs(session_id)
);

CREATE INDEX IF NOT EXISTS idx_sessions_agent ON nexus_memory.session_logs(agent_id);
CREATE INDEX IF NOT EXISTS idx_sessions_started ON nexus_memory.session_logs(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_sessions_project ON nexus_memory.session_logs(related_project);

-- ----------------------------------------------------------------------------
-- 7. Table: daily_ssot — Single Source of Truth (cortex pattern)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS nexus_memory.daily_ssot (
  ssot_id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  ssot_date       DATE NOT NULL UNIQUE,
  generated_at    TIMESTAMPTZ DEFAULT NOW(),
  content         TEXT NOT NULL,
  embedding       vector(1536),
  agents_active   INTEGER DEFAULT 0,
  total_sessions  INTEGER DEFAULT 0,
  key_decisions   JSONB DEFAULT '[]'::jsonb,
  blockers        JSONB DEFAULT '[]'::jsonb,
  next_actions    JSONB DEFAULT '[]'::jsonb,
  generated_by    UUID REFERENCES nexus_memory.agent_profiles(agent_id)
);

CREATE INDEX IF NOT EXISTS idx_ssot_date ON nexus_memory.daily_ssot(ssot_date DESC);

-- ----------------------------------------------------------------------------
-- 8. Table: knowledge_base — Static expertise per role
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS nexus_memory.knowledge_base (
  knowledge_id    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  applies_to_level nexus_memory.agent_level,
  applies_to_role TEXT,
  category        TEXT NOT NULL,
  title           TEXT NOT NULL,
  content         TEXT NOT NULL,
  embedding       vector(1536),
  source          TEXT,
  source_url      TEXT,
  authority_rank  INTEGER DEFAULT 5,
  tags            TEXT[] DEFAULT ARRAY[]::TEXT[],
  status          nexus_memory.memory_status DEFAULT 'active',
  created_at      TIMESTAMPTZ DEFAULT NOW(),
  updated_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_kb_level ON nexus_memory.knowledge_base(applies_to_level);
CREATE INDEX IF NOT EXISTS idx_kb_category ON nexus_memory.knowledge_base(category);
CREATE INDEX IF NOT EXISTS idx_kb_tags ON nexus_memory.knowledge_base USING GIN(tags);
CREATE INDEX IF NOT EXISTS idx_kb_embedding ON nexus_memory.knowledge_base
  USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- ----------------------------------------------------------------------------
-- 9. Table: escalations — Issues escalated up the hierarchy
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS nexus_memory.escalations (
  escalation_id   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  raised_by       UUID NOT NULL REFERENCES nexus_memory.agent_profiles(agent_id),
  escalated_to    UUID REFERENCES nexus_memory.agent_profiles(agent_id),
  priority        nexus_memory.escalation_priority DEFAULT 'medium',
  category        TEXT NOT NULL,
  title           TEXT NOT NULL,
  description     TEXT NOT NULL,
  related_session UUID REFERENCES nexus_memory.session_logs(session_id),
  related_project TEXT,
  status          TEXT DEFAULT 'open',
  resolution      TEXT,
  resolved_at     TIMESTAMPTZ,
  created_at      TIMESTAMPTZ DEFAULT NOW(),
  updated_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_escalations_status ON nexus_memory.escalations(status);
CREATE INDEX IF NOT EXISTS idx_escalations_priority ON nexus_memory.escalations(priority);
CREATE INDEX IF NOT EXISTS idx_escalations_raised_by ON nexus_memory.escalations(raised_by);

-- ----------------------------------------------------------------------------
-- 10. Helper Function: semantic_search
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION nexus_memory.semantic_search(
  query_embedding vector(1536),
  filter_agent_id UUID DEFAULT NULL,
  filter_level    nexus_memory.agent_level DEFAULT NULL,
  match_threshold FLOAT DEFAULT 0.7,
  match_count     INT DEFAULT 10
)
RETURNS TABLE (
  memory_id   UUID,
  agent_id    UUID,
  content     TEXT,
  similarity  FLOAT,
  memory_type TEXT,
  created_at  TIMESTAMPTZ
)
LANGUAGE sql STABLE AS $$
  SELECT
    m.memory_id,
    m.agent_id,
    m.content,
    1 - (m.embedding <=> query_embedding) AS similarity,
    m.memory_type,
    m.created_at
  FROM nexus_memory.agent_memories m
  JOIN nexus_memory.agent_profiles p ON p.agent_id = m.agent_id
  WHERE m.status = 'active'
    AND (filter_agent_id IS NULL OR m.agent_id = filter_agent_id)
    AND (filter_level IS NULL OR p.level = filter_level)
    AND 1 - (m.embedding <=> query_embedding) > match_threshold
  ORDER BY m.embedding <=> query_embedding
  LIMIT match_count;
$$;

-- ----------------------------------------------------------------------------
-- 11. Trigger: Auto-update updated_at columns
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION nexus_memory.set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_agent_profiles_updated ON nexus_memory.agent_profiles;
CREATE TRIGGER trg_agent_profiles_updated
  BEFORE UPDATE ON nexus_memory.agent_profiles
  FOR EACH ROW EXECUTE FUNCTION nexus_memory.set_updated_at();

DROP TRIGGER IF EXISTS trg_memories_updated ON nexus_memory.agent_memories;
CREATE TRIGGER trg_memories_updated
  BEFORE UPDATE ON nexus_memory.agent_memories
  FOR EACH ROW EXECUTE FUNCTION nexus_memory.set_updated_at();

DROP TRIGGER IF EXISTS trg_kb_updated ON nexus_memory.knowledge_base;
CREATE TRIGGER trg_kb_updated
  BEFORE UPDATE ON nexus_memory.knowledge_base
  FOR EACH ROW EXECUTE FUNCTION nexus_memory.set_updated_at();

DROP TRIGGER IF EXISTS trg_escalations_updated ON nexus_memory.escalations;
CREATE TRIGGER trg_escalations_updated
  BEFORE UPDATE ON nexus_memory.escalations
  FOR EACH ROW EXECUTE FUNCTION nexus_memory.set_updated_at();

-- ----------------------------------------------------------------------------
-- 12. Row-Level Security (RLS) — locked down by default
-- ----------------------------------------------------------------------------
ALTER TABLE nexus_memory.agent_profiles  ENABLE ROW LEVEL SECURITY;
ALTER TABLE nexus_memory.agent_memories  ENABLE ROW LEVEL SECURITY;
ALTER TABLE nexus_memory.session_logs    ENABLE ROW LEVEL SECURITY;
ALTER TABLE nexus_memory.daily_ssot      ENABLE ROW LEVEL SECURITY;
ALTER TABLE nexus_memory.knowledge_base  ENABLE ROW LEVEL SECURITY;
ALTER TABLE nexus_memory.escalations     ENABLE ROW LEVEL SECURITY;

-- Service-role policies (server-side access only; client SDK is denied by default)
DROP POLICY IF EXISTS srv_all_profiles  ON nexus_memory.agent_profiles;
CREATE POLICY srv_all_profiles  ON nexus_memory.agent_profiles  FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS srv_all_memories  ON nexus_memory.agent_memories;
CREATE POLICY srv_all_memories  ON nexus_memory.agent_memories  FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS srv_all_sessions  ON nexus_memory.session_logs;
CREATE POLICY srv_all_sessions  ON nexus_memory.session_logs    FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS srv_all_ssot      ON nexus_memory.daily_ssot;
CREATE POLICY srv_all_ssot      ON nexus_memory.daily_ssot      FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS srv_all_kb        ON nexus_memory.knowledge_base;
CREATE POLICY srv_all_kb        ON nexus_memory.knowledge_base  FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS srv_all_escal     ON nexus_memory.escalations;
CREATE POLICY srv_all_escal     ON nexus_memory.escalations     FOR ALL TO service_role USING (true) WITH CHECK (true);

-- ----------------------------------------------------------------------------
-- DONE — Schema ready for agent population
-- ----------------------------------------------------------------------------
