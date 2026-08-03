package com.jarvis.app;

import com.jarvis.agents.MultiAgentManager;
import com.jarvis.agents.Role;
import com.jarvis.api.ChatRequest;
import com.jarvis.api.JarvisApi;
import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.tools.RiskTier;

/**
 * App-level facade over the multi-agent manager. Every role turn runs through the governed agent
 * ({@link JarvisApi#chat} — Phase 1 permission gate + audit + per-turn tool budget) and is
 * additionally recorded to the audit log as an AUTONOMOUS action. The manager's MAX_TURNS ceiling is
 * the run-level hard budget, so an autonomous multi-agent conversation can never run away.
 */
final class MultiAgentService {

    private final MultiAgentManager manager = new MultiAgentManager();
    private final JarvisApi api;
    private final AuditLog audit;   // nullable

    MultiAgentService(JarvisApi api, AuditLog audit) {
        this.api = api;
        this.audit = audit;
    }

    MultiAgentManager.Conversation run(String goal) {
        return manager.run(goal, (role, prompt) -> {
            String out = api.chat(new ChatRequest("agents",
                    "You are the " + role + " in a small agent team. " + prompt)).response();
            if (audit != null) {
                audit.record(new AuditEvent(AuditCategory.SYSTEM, "agent:" + role,
                        AuditTrigger.AUTONOMOUS, riskFor(role), AuditOutcome.SUCCESS,
                        "multi-agent turn for goal: " + goal));
            }
            return out;
        });
    }

    /**
     * Classifies a sub-role turn into the governance risk taxonomy instead of leaving it
     * {@code UNKNOWN}. The PLANNER and CRITIC only read and reason (they produce a plan or a
     * critique with no side effects), so their turns are {@link RiskTier#READ_ONLY}. The EXECUTOR
     * carries out the plan and may invoke mutating tools, so it is classified {@link
     * RiskTier#MUTATING} — the conservative choice for an actor that can change state.
     */
    static RiskTier riskFor(Role role) {
        return switch (role) {
            case PLANNER, CRITIC -> RiskTier.READ_ONLY;
            case EXECUTOR -> RiskTier.MUTATING;
        };
    }

    static String roleName(Role role) {
        return role.name();
    }
}
