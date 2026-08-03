// score-eval-run/index.ts
// Deployable Supabase Edge Function: triggers an eval run on a single case
// and persists scored results to nexus_memory.eval_runs.
//
// Endpoint: POST /functions/v1/score-eval-run
// Auth:     Authorization: Bearer <SUPABASE_ANON_OR_SERVICE_KEY>
// Body:     { "case_external_id": "P1-01", "agent_code": "CEO-001", "dry_run": false }
//
// Returns:  { ok: true, run: { ... } }
//
// Required environment variables (set via `supabase secrets set`):
//   SUPABASE_URL
//   SUPABASE_SERVICE_ROLE_KEY
//   ANTHROPIC_API_KEY     (for claude-* models)
//   OPENAI_API_KEY        (for gpt-* / o-* models)
//   GOOGLE_API_KEY        (for gemini-* models)

// deno-lint-ignore-file no-explicit-any

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const ANTHROPIC_KEY = Deno.env.get("ANTHROPIC_API_KEY") ?? "";
const OPENAI_KEY = Deno.env.get("OPENAI_API_KEY") ?? "";
const GOOGLE_KEY = Deno.env.get("GOOGLE_API_KEY") ?? "";

const PASS_THRESHOLD = 0.70;

const STOP = new Set([
  "the","and","for","with","that","this","from","into","must","any","all",
  "not","are","has","have","will","should","use","uses","include","includes",
  "mentions","calls","out","via","per","least","more","than","without",
  "about","every","each",
]);

const TOKEN_RE = /[a-zA-Z][a-zA-Z0-9_-]{2,}/g;

function keywords(text: string): string[] {
  const toks = (text.match(TOKEN_RE) ?? []).map(t => t.toLowerCase());
  const filtered = toks.filter(t => !STOP.has(t));
  return (filtered.length ? filtered : toks).slice(0, 3);
}

function looseHit(text: string, lower: string): boolean {
  const kws = keywords(text);
  return kws.length > 0 && kws.some(k => lower.includes(k));
}

function failureModeHit(text: string, lower: string): boolean {
  const kws = keywords(text);
  if (kws.length < 2) return false;
  let n = 0;
  for (const k of kws) if (lower.includes(k)) n++;
  return n >= 2;
}

interface Criterion { name: string; checks: string[]; weight: number; }
interface Case {
  case_id: string;
  external_id: string;
  pillar: number;
  pillar_name?: string;
  role: string;
  level: number;
  input: any;
  expected_output: { must_include?: string[]; must_not_include?: string[] };
  rubric: { scoring_scale: string; criteria: Criterion[] };
  failure_modes: string[];
}
interface Agent {
  agent_id: string;
  agent_code: string;
  level: string;
  model: string;
}

const ROLE_GUIDANCE: Record<string, string> = {
  ceo: "You are the CEO of a 229-agent hierarchical orchestration system. You only delegate to the Executive — never to Workers directly. You apply HITL gating to high-impact decisions.",
  executive: "You are the Executive. You compress Worker logs into structured summaries and allocate work to Project Managers. You do not micromanage Workers.",
  project_manager: "You are a Project Manager. You translate Executive goals into department plans and assign work to Project Engineers — never directly to Workers.",
  project_engineer: "You are a Project Engineer. You decompose tasks into specifications and assign work to Superintendents. You enforce QA gates.",
  superintendent: "You are a Superintendent. You supervise ~8 Workers in real time, enforce sync logic, and resolve immediate constraints.",
  worker: "You are a Worker. You execute tasks within scope, report status with evidence, and provide concrete next steps.",
};

function buildPrompt(c: Case, a: Agent): string {
  const guidance = ROLE_GUIDANCE[a.level] ?? "";
  const parts = [
    `# Role\n${guidance}`,
    `# Task\n${c.input.task}`,
  ];
  if (c.input.context) parts.push(`# Context\n${JSON.stringify(c.input.context, null, 2)}`);
  if (c.input.rag_context) parts.push(`# Retrieved Context (use ONLY this for factual claims)\n${JSON.stringify(c.input.rag_context, null, 2)}`);
  if (c.input.memory) parts.push(`# Memory\n${JSON.stringify(c.input.memory, null, 2)}`);
  parts.push("# Required Output Sections");
  for (const item of c.expected_output.must_include ?? []) parts.push(`- ${item}`);
  if (c.expected_output.must_not_include?.length) {
    parts.push("# Must NOT Include");
    for (const item of c.expected_output.must_not_include) parts.push(`- ${item}`);
  }
  parts.push("# Evaluation Criteria You Will Be Scored On");
  for (const cr of c.rubric.criteria) parts.push(`- ${cr.name}`);
  parts.push("\nProvide your response now.");
  return parts.join("\n\n");
}

async function callClaude(model: string, prompt: string): Promise<string> {
  if (!ANTHROPIC_KEY) throw new Error("ANTHROPIC_API_KEY not set");
  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: { "x-api-key": ANTHROPIC_KEY, "anthropic-version": "2023-06-01", "content-type": "application/json" },
    body: JSON.stringify({ model, max_tokens: 2048, messages: [{ role: "user", content: prompt }] }),
  });
  if (!res.ok) throw new Error(`Anthropic ${res.status}: ${await res.text()}`);
  const data = await res.json();
  return (data.content ?? []).map((b: any) => b.text ?? "").join("");
}

async function callOpenAI(model: string, prompt: string): Promise<string> {
  if (!OPENAI_KEY) throw new Error("OPENAI_API_KEY not set");
  const res = await fetch("https://api.openai.com/v1/chat/completions", {
    method: "POST",
    headers: { Authorization: `Bearer ${OPENAI_KEY}`, "Content-Type": "application/json" },
    body: JSON.stringify({ model, messages: [{ role: "user", content: prompt }], max_completion_tokens: 2048 }),
  });
  if (!res.ok) throw new Error(`OpenAI ${res.status}: ${await res.text()}`);
  const data = await res.json();
  return data.choices?.[0]?.message?.content ?? "";
}

async function callGemini(model: string, prompt: string): Promise<string> {
  if (!GOOGLE_KEY) throw new Error("GOOGLE_API_KEY not set");
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${GOOGLE_KEY}`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ contents: [{ parts: [{ text: prompt }] }], generationConfig: { maxOutputTokens: 2048 } }),
  });
  if (!res.ok) throw new Error(`Gemini ${res.status}: ${await res.text()}`);
  const data = await res.json();
  return (data.candidates?.[0]?.content?.parts ?? []).map((p: any) => p.text ?? "").join("");
}

async function dispatch(model: string, prompt: string): Promise<string> {
  if (model.startsWith("claude")) return callClaude(model, prompt);
  if (model.startsWith("gpt") || model.startsWith("o")) return callOpenAI(model, prompt);
  if (model.startsWith("gemini")) return callGemini(model, prompt);
  throw new Error(`Unknown model family: ${model}`);
}

function scoreResponse(response: string, c: Case) {
  const lower = response.toLowerCase();
  const mustInclude = c.expected_output.must_include ?? [];
  const mustNot = c.expected_output.must_not_include ?? [];
  const scores: Record<string, number> = {};
  let total = 0, max = 0;
  for (const cr of c.rubric.criteria) {
    const w = Number(cr.weight ?? 2);
    max += w;
    let hits = 0;
    for (const chk of cr.checks) {
      const kws = keywords(chk);
      if (kws.length && kws.some(k => lower.includes(k))) hits++;
    }
    const ratio = hits / Math.max(1, cr.checks.length);
    const sc = Math.round(ratio * w * 100) / 100;
    scores[cr.name] = sc;
    total += sc;
  }
  const miHits = mustInclude.filter(s => looseHit(s, lower)).length;
  if (miHits / Math.max(1, mustInclude.length) < 0.5) total *= 0.7;
  for (const f of mustNot) if (looseHit(f, lower)) total *= 0.5;
  const fmHits = c.failure_modes.filter(f => failureModeHit(f, lower));
  if (fmHits.length) total = Math.max(0, total - fmHits.length);
  return { scores, total: Math.round(Math.min(total, max) * 100) / 100, max, fmHits };
}

async function sb(path: string, opts: { method?: string; body?: any; query?: string } = {}) {
  const url = `${SUPABASE_URL}/rest/v1/${path}${opts.query ? `?${opts.query}` : ""}`;
  const res = await fetch(url, {
    method: opts.method ?? "GET",
    headers: {
      apikey: SERVICE_ROLE,
      Authorization: `Bearer ${SERVICE_ROLE}`,
      "Content-Type": "application/json",
      "Accept-Profile": "nexus_memory",
      "Content-Profile": "nexus_memory",
      Prefer: "return=representation",
    },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  if (!res.ok) throw new Error(`Supabase ${path} ${res.status}: ${await res.text()}`);
  return res.json();
}

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return new Response(JSON.stringify({ ok: false, error: "POST only" }), { status: 405 });
  }
  try {
    const { case_external_id, agent_code, dry_run } = await req.json();
    if (!case_external_id || !agent_code) {
      return new Response(JSON.stringify({ ok: false, error: "case_external_id and agent_code required" }), { status: 400 });
    }
    const cases = await sb("eval_cases", { query: `select=*&external_id=eq.${case_external_id}` });
    if (!cases.length) throw new Error(`No case found: ${case_external_id}`);
    const agents = await sb("agent_profiles", { query: `select=*&agent_code=eq.${agent_code}` });
    if (!agents.length) throw new Error(`No agent found: ${agent_code}`);

    const c: Case = cases[0]; const a: Agent = agents[0];
    const prompt = buildPrompt(c, a);
    const start = Date.now();
    let response: string;
    try { response = await dispatch(a.model, prompt); }
    catch (e) { response = `<<MODEL_CALL_FAILED: ${e instanceof Error ? e.message : String(e)}>>`; }
    const duration_ms = Date.now() - start;
    const { scores, total, max, fmHits } = scoreResponse(response, c);
    const passed = max > 0 ? total / max >= PASS_THRESHOLD : false;

    let runRow: any = { case_external_id: c.external_id, agent_code: a.agent_code,
      model_used: a.model, scores, total_score: total, max_score: max,
      passed, failure_modes_hit: fmHits, duration_ms, response };

    if (!dry_run) {
      const inserted = await sb("eval_runs", {
        method: "POST",
        body: { case_id: c.case_id, agent_id: a.agent_id, model_used: a.model,
          agent_response: { text: response }, scores, total_score: total, max_score: max,
          passed, failure_modes_hit: fmHits, duration_ms },
      });
      runRow = { ...runRow, run_id: inserted[0]?.run_id };
    }

    return new Response(JSON.stringify({ ok: true, run: runRow }), {
      status: 200, headers: { "Content-Type": "application/json" },
    });
  } catch (err) {
    return new Response(JSON.stringify({ ok: false, error: err instanceof Error ? err.message : String(err) }),
      { status: 500, headers: { "Content-Type": "application/json" } });
  }
});
