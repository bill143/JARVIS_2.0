package com.jarvis.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jarvis.tools.RiskTier;
import org.junit.jupiter.api.Test;

class PermissionPolicyTest {

    @Test
    void defaultLevelGatesDestructiveAndUnknownButNotMutating() {
        PermissionPolicy p = new PermissionPolicy();   // DESTRUCTIVE
        assertEquals(PermissionDecision.ALLOW, p.decide(RiskTier.READ_ONLY));
        assertEquals(PermissionDecision.ALLOW, p.decide(RiskTier.MUTATING));
        assertEquals(PermissionDecision.PROMPT, p.decide(RiskTier.DESTRUCTIVE));
        assertEquals(PermissionDecision.PROMPT, p.decide(RiskTier.UNKNOWN));
    }

    @Test
    void mutatingLevelGatesMutatingToo() {
        PermissionPolicy p = new PermissionPolicy(PermissionLevel.MUTATING);
        assertEquals(PermissionDecision.ALLOW, p.decide(RiskTier.READ_ONLY));
        assertEquals(PermissionDecision.PROMPT, p.decide(RiskTier.MUTATING));
        assertEquals(PermissionDecision.PROMPT, p.decide(RiskTier.DESTRUCTIVE));
    }

    @Test
    void offLevelNeverPrompts() {
        PermissionPolicy p = new PermissionPolicy(PermissionLevel.OFF);
        assertEquals(PermissionDecision.ALLOW, p.decide(RiskTier.DESTRUCTIVE));
        assertEquals(PermissionDecision.ALLOW, p.decide(RiskTier.UNKNOWN));
    }

    @Test
    void levelIsMutableLive() {
        PermissionPolicy p = new PermissionPolicy();
        assertEquals(PermissionDecision.ALLOW, p.decide(RiskTier.MUTATING));
        p.setLevel(PermissionLevel.MUTATING);
        assertEquals(PermissionDecision.PROMPT, p.decide(RiskTier.MUTATING));
    }
}
