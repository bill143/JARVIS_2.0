export type TaskStatus = "queued" | "running" | "done" | "failed" | "retrying" | "recovered";

export type AgentTask = {
  id: string;
  title: string;
  status: TaskStatus;
  steps: string[];
  error?: string;
};
