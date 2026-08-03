/**
 * MODULE 8B — SEMANTIC MEMORY (Knowledge Graph)
 * "What is true" — Distilled facts about projects, clients, subs, contracts
 *
 * Engine: Neo4j Knowledge Graph
 *
 * Entity Relationships:
 *   (Project) -[HAS_SUBCONTRACTOR]→ (Subcontractor)
 *   (Subcontractor) -[HOLDS_COI]→ (InsuranceCertificate)
 *   (InsuranceCertificate) -[EXPIRES_ON]→ (Date)
 *   (Project) -[GOVERNED_BY]→ (Contract)
 *   (Contract) -[REFERENCES]→ (FARClause)
 *   (Estimate) -[USES_RATE]→ (DavisBaconWageRate)
 *   (RFI) -[BLOCKS]→ (SubmittalItem)
 *   (SubmittalItem) -[AFFECTS]→ (ScheduleActivity)
 */

import neo4j, { Driver, Session } from 'neo4j-driver';

export interface KnowledgeGraphConfig {
  uri: string;
  user: string;
  password: string;
}

export interface GraphNode {
  label: string;
  properties: Record<string, unknown>;
}

export interface GraphRelation {
  from: { label: string; id: string };
  to: { label: string; id: string };
  type: string;
  properties?: Record<string, unknown>;
}

export class KnowledgeGraph {
  private driver: Driver;

  constructor(config: KnowledgeGraphConfig) {
    this.driver = neo4j.driver(config.uri, neo4j.auth.basic(config.user, config.password));
  }

  /** Create or update a node */
  async upsertNode(label: string, id: string, properties: Record<string, unknown>): Promise<void> {
    const session = this.driver.session();
    try {
      await session.run(
        `MERGE (n:${label} {id: $id}) SET n += $props`,
        { id, props: properties },
      );
    } finally {
      await session.close();
    }
  }

  /** Create a relationship between two nodes */
  async createRelation(relation: GraphRelation): Promise<void> {
    const session = this.driver.session();
    try {
      await session.run(
        `MATCH (a:${relation.from.label} {id: $fromId})
         MATCH (b:${relation.to.label} {id: $toId})
         MERGE (a)-[r:${relation.type}]->(b)
         SET r += $props`,
        {
          fromId: relation.from.id,
          toId: relation.to.id,
          props: relation.properties ?? {},
        },
      );
    } finally {
      await session.close();
    }
  }

  /** Query the graph with Cypher */
  async query(cypher: string, params?: Record<string, unknown>): Promise<any[]> {
    const session = this.driver.session();
    try {
      const result = await session.run(cypher, params ?? {});
      return result.records.map(r => r.toObject());
    } finally {
      await session.close();
    }
  }

  /** Get all relationships for a project */
  async getProjectGraph(projectId: string): Promise<{
    nodes: GraphNode[];
    relationships: GraphRelation[];
  }> {
    const result = await this.query(
      `MATCH (p:Project {id: $projectId})-[r*1..3]-(connected)
       RETURN p, r, connected`,
      { projectId },
    );

    const nodes: GraphNode[] = [];
    const relationships: GraphRelation[] = [];
    const seenNodes = new Set<string>();

    for (const record of result) {
      // Extract nodes and relationships from path results
      if (record.connected && !seenNodes.has(record.connected.properties?.id)) {
        seenNodes.add(record.connected.properties?.id);
        nodes.push({
          label: record.connected.labels?.[0] ?? 'Unknown',
          properties: record.connected.properties ?? {},
        });
      }
    }

    return { nodes, relationships };
  }

  /** Find expiring COIs — proactive alert trigger */
  async findExpiringCOIs(daysAhead: number = 30): Promise<Array<{
    subcontractor: string;
    expirationDate: string;
    project: string;
  }>> {
    const cutoff = new Date();
    cutoff.setDate(cutoff.getDate() + daysAhead);

    return this.query(
      `MATCH (s:Subcontractor)-[:HOLDS_COI]->(c:InsuranceCertificate)-[:EXPIRES_ON]->(d:Date)
       WHERE d.value <= $cutoff
       MATCH (p:Project)-[:HAS_SUBCONTRACTOR]->(s)
       RETURN s.name AS subcontractor, d.value AS expirationDate, p.name AS project
       ORDER BY d.value ASC`,
      { cutoff: cutoff.toISOString().split('T')[0] },
    );
  }

  /** Find RFIs blocking submittals */
  async findBlockedSubmittals(): Promise<Array<{
    rfi: string;
    submittal: string;
    scheduleActivity: string;
  }>> {
    return this.query(
      `MATCH (r:RFI)-[:BLOCKS]->(s:SubmittalItem)-[:AFFECTS]->(a:ScheduleActivity)
       WHERE r.status <> 'resolved'
       RETURN r.title AS rfi, s.title AS submittal, a.name AS scheduleActivity`,
    );
  }

  /** Health check */
  async healthCheck(): Promise<boolean> {
    const session = this.driver.session();
    try {
      await session.run('RETURN 1');
      return true;
    } catch {
      return false;
    } finally {
      await session.close();
    }
  }

  /** Close the driver */
  async close(): Promise<void> {
    await this.driver.close();
  }
}
