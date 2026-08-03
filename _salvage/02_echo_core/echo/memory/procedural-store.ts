/**
 * MODULE 8C — PROCEDURAL MEMORY
 * "How to do it" — Learned SOPs, workflows, and shortcuts ECHO has mastered
 *
 * Engine: LangGraph saved workflow library
 */

export interface Procedure {
  id: string;
  name: string;
  description: string;
  trigger: string;
  steps: ProcedureStep[];
  learnedFrom: string;    // thread_id where this was first executed
  successCount: number;
  lastExecuted: Date | null;
  createdAt: Date;
}

export interface ProcedureStep {
  order: number;
  action: string;
  module: 'vault' | 'operator' | 'vision' | 'world_feed' | 'code_runner' | 'direct';
  params: Record<string, unknown>;
  expectedOutcome: string;
}

export class ProceduralStore {
  private procedures: Map<string, Procedure> = new Map();

  /** Register a new learned procedure */
  register(procedure: Omit<Procedure, 'id' | 'successCount' | 'lastExecuted' | 'createdAt'>): string {
    const id = crypto.randomUUID();
    this.procedures.set(id, {
      ...procedure,
      id,
      successCount: 0,
      lastExecuted: null,
      createdAt: new Date(),
    });
    return id;
  }

  /** Find procedures matching a trigger pattern */
  findByTrigger(userInput: string): Procedure[] {
    const input = userInput.toLowerCase();
    return [...this.procedures.values()].filter(p =>
      input.includes(p.trigger.toLowerCase()) ||
      p.trigger.toLowerCase().includes(input)
    ).sort((a, b) => b.successCount - a.successCount);
  }

  /** Record successful execution */
  recordSuccess(procedureId: string): void {
    const proc = this.procedures.get(procedureId);
    if (proc) {
      proc.successCount++;
      proc.lastExecuted = new Date();
    }
  }

  /** Get all procedures */
  list(): Procedure[] {
    return [...this.procedures.values()];
  }

  /** Export procedures for persistence */
  export(): Procedure[] {
    return [...this.procedures.values()];
  }

  /** Import procedures from persistence */
  import(procedures: Procedure[]): void {
    for (const proc of procedures) {
      this.procedures.set(proc.id, proc);
    }
  }
}
