/**
 * MODULE 8A — EPISODIC MEMORY
 * "What happened" — Every conversation, action, decision, and outcome logged
 *
 * Engine: pgvector (timestamped embeddings) via Supabase
 */

import { createClient, SupabaseClient } from '@supabase/supabase-js';

export interface Episode {
  id: string;
  threadId: string;
  userId: string;
  timestamp: Date;
  type: 'conversation' | 'action' | 'decision' | 'outcome';
  content: string;
  context: {
    projectId?: string;
    module?: string;
    trigger?: string;
  };
  embedding?: number[];
}

export interface EpisodicQuery {
  query: string;
  userId?: string;
  projectId?: string;
  timeRange?: { from: Date; to: Date };
  limit?: number;
}

export class EpisodicStore {
  private supabase: SupabaseClient;

  constructor(supabaseUrl: string, supabaseKey: string) {
    this.supabase = createClient(supabaseUrl, supabaseKey);
  }

  /** Log a new episode */
  async log(episode: Omit<Episode, 'id' | 'embedding'>): Promise<string> {
    const embedding = await this.embed(episode.content);
    const id = crypto.randomUUID();

    await this.supabase.from('episodic_memory').insert({
      id,
      thread_id: episode.threadId,
      user_id: episode.userId,
      timestamp: episode.timestamp.toISOString(),
      type: episode.type,
      content: episode.content,
      context: episode.context,
      embedding,
    });

    return id;
  }

  /** Recall similar episodes via semantic search */
  async recall(query: EpisodicQuery): Promise<Episode[]> {
    const embedding = await this.embed(query.query);

    const { data, error } = await this.supabase.rpc('match_episodic_memory', {
      query_embedding: embedding,
      match_threshold: 0.5,
      match_count: query.limit ?? 10,
      filter_user_id: query.userId ?? null,
      filter_project_id: query.projectId ?? null,
    });

    if (error) throw new Error(`Episodic recall failed: ${error.message}`);

    return (data ?? []).map((row: any) => ({
      id: row.id,
      threadId: row.thread_id,
      userId: row.user_id,
      timestamp: new Date(row.timestamp),
      type: row.type,
      content: row.content,
      context: row.context,
      similarity: row.similarity,
    }));
  }

  /** Load all episodes for a given thread */
  async loadThread(threadId: string): Promise<Episode[]> {
    const { data, error } = await this.supabase
      .from('episodic_memory')
      .select('*')
      .eq('thread_id', threadId)
      .order('timestamp', { ascending: true });

    if (error) throw new Error(`Thread load failed: ${error.message}`);

    return (data ?? []).map((row: any) => ({
      id: row.id,
      threadId: row.thread_id,
      userId: row.user_id,
      timestamp: new Date(row.timestamp),
      type: row.type,
      content: row.content,
      context: row.context,
    }));
  }

  /** Health check */
  async healthCheck(): Promise<boolean> {
    try {
      const { error } = await this.supabase.from('episodic_memory').select('id').limit(1);
      return !error;
    } catch {
      return false;
    }
  }

  private async embed(text: string): Promise<number[]> {
    const { data, error } = await this.supabase.functions.invoke('embed', {
      body: { text },
    });
    if (error) throw new Error(`Embedding failed: ${error.message}`);
    return data.embedding;
  }
}
