/**
 * MODULE 9 — THE CODE INTERPRETER
 * Secure On-Demand Computation & Scripting Engine
 *
 * Runtime: Python + Node.js sandboxed execution via E2B
 */

import { Sandbox } from '@e2b/code-interpreter';

export interface CodeExecResult {
  success: boolean;
  output: string;
  error?: string;
  logs: string[];
  artifacts: Array<{ type: string; data: string }>;
}

export class EchoCodeRunner {
  private apiKey: string;
  private sandbox: Sandbox | null = null;

  constructor(apiKey: string) {
    this.apiKey = apiKey;
  }

  /** Initialize the E2B sandbox */
  async init(): Promise<void> {
    this.sandbox = await Sandbox.create({ apiKey: this.apiKey });
    console.log('[ECHO-CODE-RUNNER] E2B sandbox initialized');
  }

  /** Execute Python code in sandbox */
  async runPython(code: string): Promise<CodeExecResult> {
    if (!this.sandbox) await this.init();

    const execution = await this.sandbox!.runCode(code, { language: 'python' });

    const logs: string[] = [];
    const artifacts: Array<{ type: string; data: string }> = [];

    for (const result of execution.results) {
      if (result.text) logs.push(result.text);
      if (result.png) artifacts.push({ type: 'image/png', data: result.png });
      if (result.html) artifacts.push({ type: 'text/html', data: result.html });
    }

    return {
      success: !execution.error,
      output: logs.join('\n'),
      error: execution.error?.value,
      logs: execution.logs.stdout.concat(execution.logs.stderr),
      artifacts,
    };
  }

  /** Execute JavaScript/TypeScript code in sandbox */
  async runJavaScript(code: string): Promise<CodeExecResult> {
    if (!this.sandbox) await this.init();

    const execution = await this.sandbox!.runCode(code, { language: 'js' });

    const logs: string[] = [];
    const artifacts: Array<{ type: string; data: string }> = [];

    for (const result of execution.results) {
      if (result.text) logs.push(result.text);
    }

    return {
      success: !execution.error,
      output: logs.join('\n'),
      error: execution.error?.value,
      logs: execution.logs.stdout.concat(execution.logs.stderr),
      artifacts,
    };
  }

  /** Pre-built: Calculate retention across active projects */
  async calculateRetention(scheduleOfValuesData: string): Promise<CodeExecResult> {
    const code = `
import pandas as pd
import json

data = json.loads('''${scheduleOfValuesData}''')
df = pd.DataFrame(data)
df['retention_held'] = df['contract_value'] * df['retention_pct']
total = df['retention_held'].sum()
count = len(df)
print(f"Total retention currently held: ${'{'}total:,.2f{'}'} across {'{'}count{'}'} projects")
print(json.dumps({"total_retention": total, "project_count": count, "breakdown": df[['project_name', 'retention_held']].to_dict('records')}))
`;
    return this.runPython(code);
  }

  /** Pre-built: Davis-Bacon wage comparison */
  async compareDavisBaconRates(laborData: string, wageDecisionData: string): Promise<CodeExecResult> {
    const code = `
import pandas as pd
import json

labor = pd.DataFrame(json.loads('''${laborData}'''))
wd = pd.DataFrame(json.loads('''${wageDecisionData}'''))

merged = labor.merge(wd, on='classification', suffixes=('_actual', '_required'))
merged['compliant'] = merged['rate_actual'] >= merged['rate_required']
merged['shortfall'] = (merged['rate_required'] - merged['rate_actual']).clip(lower=0)

violations = merged[~merged['compliant']]
print(f"Davis-Bacon Compliance: {len(merged) - len(violations)}/{len(merged)} classifications compliant")
if len(violations) > 0:
    print(f"VIOLATIONS FOUND: {len(violations)}")
    print(violations[['classification', 'rate_actual', 'rate_required', 'shortfall']].to_string())
print(json.dumps({"compliant": len(violations) == 0, "violations": violations.to_dict('records')}))
`;
    return this.runPython(code);
  }

  /** Teardown sandbox */
  async teardown(): Promise<void> {
    if (this.sandbox) {
      await this.sandbox.kill();
      this.sandbox = null;
      console.log('[ECHO-CODE-RUNNER] E2B sandbox closed');
    }
  }

  /** Health check */
  async healthCheck(): Promise<boolean> {
    try {
      if (!this.sandbox) await this.init();
      const result = await this.runPython('print("ECHO sandbox alive")');
      return result.success;
    } catch {
      return false;
    }
  }
}
