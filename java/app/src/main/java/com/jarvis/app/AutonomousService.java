package com.jarvis.app;

import com.jarvis.api.ChatRequest;
import com.jarvis.api.JarvisApi;
import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.autonomous.AutonomousRunner;
import com.jarvis.tools.RiskTier;

/**
 * App-level facade over the autonomous runner. Each iteration runs through the governed agent
 * ({@link JarvisApi#chat} — Phase 1 permission gate + audit + per-turn tool budget) and is recorded
 * to the audit log as an AUTONOMOUS action. The runner's MAX_STEPS ceiling is the run-level hard
 * budget: the loop cannot run away.
 */
final class AutonomousService {

    private final AutonomousRunner runner = new AutonomousRunner();
    private final JarvisApi api;
    private final AuditLog audit;   // nullable

    AutonomousService(JarvisApi api, AuditLog audit) {
        this.api = api;
        this.audit = audit;
    }

    AutonomousRunner.AutonomousRun run(String goal) {
        return runner.run(goal, (g, progress) -> {
            String prompt = "Goal: " + g + "\nProgress so far:\n" + progress
                    + "\nTake the single next step toward the goal, using tools if needed. If the goal"
                    + " is fully achieved, end your reply with the token " + AutonomousRunner.DONE_MARKER + ".";
            String out = api.chat(new ChatRequest("autonomous", prompt)).response();
            if (audit != null) {
                audit.record(new AuditEvent(AuditCategory.SYSTEM, "autonomous-step",
                        AuditTrigger.AUTONOMOUS, RiskTier.UNKNOWN, AuditOutcome.SUCCESS, "goal: " + g));
            }
            return out;
        });
    }
}
