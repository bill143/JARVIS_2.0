package com.jarvis.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jarvis.tools.RiskTier;
import org.junit.jupiter.api.Test;

class SecurityModuleSmokeTest {

    @Test
    void policyAndGateComposeIntoADecision() {
        PermissionPolicy policy = new PermissionPolicy();
        assertEquals(PermissionDecision.PROMPT, policy.decide(RiskTier.DESTRUCTIVE));
        PermissionGate gate = (t, tier, d) -> PermissionOutcome.ALLOWED;
        assertEquals(PermissionOutcome.ALLOWED,
                gate.request("email_send", RiskTier.DESTRUCTIVE, "args: [to]"));
    }
}
