package com.jarvis.app;

import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.presence.ContextGuards;
import com.jarvis.presence.GreetingOrchestrator;
import com.jarvis.presence.GreetingOutcome;
import com.jarvis.presence.PresenceSignal;
import com.jarvis.tools.RiskTier;
import java.util.Objects;

/**
 * App facade for proactive greeting. Feature-flagged OFF by default; when disabled it produces no
 * greeting. <b>Every</b> proactive evaluation — greeted or suppressed — is written to the audit log
 * as an AUTONOMOUS action, so proactive behavior is always accountable. The audit detail records the
 * greeting type and the decision reason but <em>not</em> the person's name (privacy).
 */
final class GreetingService {

    private final GreetingOrchestrator orchestrator;
    private final AuditLog audit;   // nullable
    private final boolean enabled;

    GreetingService(GreetingOrchestrator orchestrator, AuditLog audit, boolean enabled) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.audit = audit;
        this.enabled = enabled;
    }

    boolean enabled() {
        return enabled;
    }

    /** Evaluates a presence event and returns the greeting outcome, auditing the proactive decision. */
    GreetingOutcome onPresence(PresenceSignal signal, ContextGuards guards) {
        if (!enabled) {
            return GreetingOutcome.silent("proactive greeting disabled");
        }
        GreetingOutcome outcome = orchestrator.onPresence(signal, guards);
        if (audit != null) {
            audit.record(new AuditEvent(AuditCategory.SYSTEM, "proactive-greeting",
                    AuditTrigger.AUTONOMOUS, RiskTier.READ_ONLY, AuditOutcome.SUCCESS,
                    outcome.type() + (outcome.greeted() ? " greeted" : " suppressed")
                            + ": " + outcome.reason()));
        }
        return outcome;
    }
}
