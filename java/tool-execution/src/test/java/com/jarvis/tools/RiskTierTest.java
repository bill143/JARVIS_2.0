package com.jarvis.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RiskTierTest {

    @Test
    void atLeastComparesBySeverity() {
        assertTrue(RiskTier.DESTRUCTIVE.atLeast(RiskTier.MUTATING));
        assertTrue(RiskTier.MUTATING.atLeast(RiskTier.MUTATING));
        assertTrue(RiskTier.MUTATING.atLeast(RiskTier.READ_ONLY));
        assertFalse(RiskTier.READ_ONLY.atLeast(RiskTier.DESTRUCTIVE));
    }

    @Test
    void unknownNeverComparesAtOrAboveAnything() {
        assertFalse(RiskTier.UNKNOWN.atLeast(RiskTier.READ_ONLY));
        assertFalse(RiskTier.DESTRUCTIVE.atLeast(RiskTier.UNKNOWN));
        assertFalse(RiskTier.UNKNOWN.atLeast(RiskTier.UNKNOWN));
    }
}
