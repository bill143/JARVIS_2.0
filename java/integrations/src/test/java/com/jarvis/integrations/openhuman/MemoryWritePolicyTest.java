package com.jarvis.integrations.openhuman;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class MemoryWritePolicyTest {

    @Test
    void denyAllRefusesEveryRoleIncludingConductor() {
        MemoryWritePolicy p = MemoryWritePolicy.denyAll();
        assertFalse(p.enabled());
        assertFalse(p.mayWrite("conductor"));
        assertFalse(p.mayWrite("planner"));
        assertFalse(p.mayWrite(null));
    }

    @Test
    void whenEnabledTheConductorMayWriteByDefault() {
        MemoryWritePolicy p = new MemoryWritePolicy(true, Set.of());
        assertTrue(p.mayWrite("conductor"));
        assertTrue(p.mayWrite("CONDUCTOR"));   // case-insensitive
    }

    @Test
    void advisorsAreNeverAuthorizedEvenWhenEnabled() {
        MemoryWritePolicy p = new MemoryWritePolicy(true, Set.of("planner"));
        assertTrue(p.mayWrite("planner"));      // explicitly authorized agent
        assertFalse(p.mayWrite("advisor"));     // not in the set → denied
        assertFalse(p.mayWrite("openhuman"));
    }

    @Test
    void denialReasonExplainsWhy() {
        assertTrue(MemoryWritePolicy.denyAll().denialReason("conductor").contains("deny-by-default"));
        assertTrue(new MemoryWritePolicy(true, Set.of()).denialReason("advisor")
                .contains("not authorized"));
    }
}
