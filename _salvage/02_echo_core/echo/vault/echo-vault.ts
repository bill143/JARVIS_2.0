/**
 * MODULE 4 — THE VAULT
 * Source-Grounded Knowledge Retrieval Layer
 *
 * Integration: ON_NotebookLM logic
 * Retrieval: Hybrid — dense vector similarity (pgvector) + keyword BM25 re-ranking
 *
 * HARD CONSTRAINT: Every ECHO response touching project specs, scope language,
 * estimate line items, or document content MUST execute a Vault retrieval pass
 * before generating output. Model-weight-only responses are architecturally prohibited.
 */

import { createClient, SupabaseClient } from '@supabase/supabase-js';

export interface VaultConfig {
  supabaseUrl: string;
  supabaseKey: string;
  embeddingModel: string;
  topK: number;
}

export interface VaultChunk {
  id: string;
  content: string;
  metadata: {
    source: string;
    documentType: string;
    projectId?: string;
    pageNumber?: number;
    csiCode?: string;
  };
  similarity: number;
}

export interface RetrievalTrace {
  queryId: string;
  query: string;
  timestamp: Date;
  chunksRetrieved: number;
  topChunkSimilarity: number;
  sources: string[];
}

export class EchoVault {
  private supabase: SupabaseClient;
  private config: VaultConfig;
  private traces: RetrievalTrace[] = [];

  constructor(config: VaultConfig) {
    this.config = config;
    this.supabase = createClient(config.supabaseUrl, config.supabaseKey);
  }

  /** Full retrieval pipeline: embed → vector search → BM25 re-rank → return grounded context */
  async retrieve(query: string, projectId?: string): Promise<{
    chunks: VaultChunk[];
    trace: RetrievalTrace;
  }> {
    const queryId = crypto.randomUUID();

    // Step 1: Embed the query
    const embedding = await this.embedQuery(query);

    // Step 2: Vector search — top-K retrieval
    const vectorResults = await this.vectorSearch(embedding, projectId);

    // Step 3: BM25 keyword re-ranking
    const reranked = this.bm25Rerank(query, vectorResults);

    // Step 4: Build retrieval trace for auditability
    const trace: RetrievalTrace = {
      queryId,
      query,
      timestamp: new Date(),
      chunksRetrieved: reranked.length,
      topChunkSimilarity: reranked[0]?.similarity ?? 0,
      sources: [...new Set(reranked.map(c => c.metadata.source))],
    };
    this.traces.push(trace);

    return { chunks: reranked, trace };
  }

  /** Format retrieved chunks as context prefix for LLM injection */
  formatAsContext(chunks: VaultChunk[]): string {
    if (chunks.length === 0) return '';
    const header = '=== VAULT GROUNDING CONTEXT (source-cited) ===\n';
    const body = chunks.map((chunk, i) =>
      `[Source ${i + 1}: ${chunk.metadata.source} | Similarity: ${chunk.similarity.toFixed(3)}]\n${chunk.content}`
    ).join('\n\n');
    return header + body + '\n=== END VAULT CONTEXT ===\n';
  }

  /** Ingest a new document into the Vault */
  async ingest(content: string, metadata: VaultChunk['metadata']): Promise<void> {
    const chunks = this.chunkDocument(content);
    for (const chunk of chunks) {
      const embedding = await this.embedQuery(chunk);
      await this.supabase.from('vault_documents').insert({
        content: chunk,
        embedding,
        metadata,
        created_at: new Date().toISOString(),
      });
    }
  }

  /** Get retrieval traces for audit log */
  getTraces(): RetrievalTrace[] {
    return [...this.traces];
  }

  /** Test connection to Supabase/pgvector */
  async healthCheck(): Promise<boolean> {
    try {
      const { error } = await this.supabase.from('vault_documents').select('id').limit(1);
      return !error;
    } catch {
      return false;
    }
  }

  // ── Private ────────────────────────────────────────────

  private async embedQuery(text: string): Promise<number[]> {
    // Uses the configured embedding model via Supabase Edge Function
    // or direct API call to embedding provider
    const { data, error } = await this.supabase.functions.invoke('embed', {
      body: { text, model: this.config.embeddingModel },
    });
    if (error) throw new Error(`Embedding failed: ${error.message}`);
    return data.embedding;
  }

  private async vectorSearch(embedding: number[], projectId?: string): Promise<VaultChunk[]> {
    let query = this.supabase.rpc('match_vault_documents', {
      query_embedding: embedding,
      match_threshold: 0.5,
      match_count: this.config.topK,
    });

    if (projectId) {
      query = query.eq('metadata->>projectId', projectId);
    }

    const { data, error } = await query;
    if (error) throw new Error(`Vector search failed: ${error.message}`);

    return (data ?? []).map((row: any) => ({
      id: row.id,
      content: row.content,
      metadata: row.metadata,
      similarity: row.similarity,
    }));
  }

  private bm25Rerank(query: string, chunks: VaultChunk[]): VaultChunk[] {
    const queryTerms = query.toLowerCase().split(/\s+/);
    const scored = chunks.map(chunk => {
      const content = chunk.content.toLowerCase();
      let bm25Score = 0;
      for (const term of queryTerms) {
        const tf = (content.match(new RegExp(term, 'g')) || []).length;
        const dl = content.split(/\s+/).length;
        const avgDl = 200; // approximate average doc length
        const k1 = 1.5;
        const b = 0.75;
        bm25Score += (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * (dl / avgDl)));
      }
      return {
        ...chunk,
        similarity: chunk.similarity * 0.7 + (bm25Score / queryTerms.length) * 0.3,
      };
    });
    return scored.sort((a, b) => b.similarity - a.similarity);
  }

  private chunkDocument(content: string, chunkSize = 1000, overlap = 200): string[] {
    const chunks: string[] = [];
    for (let i = 0; i < content.length; i += chunkSize - overlap) {
      chunks.push(content.slice(i, i + chunkSize));
    }
    return chunks;
  }
}
