package com.jarvis.solicitations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GovTribeMcpAdapterTest {

    @Test
    void dormantByDefaultReturnsEmptyNeverFabricates() throws Exception {
        GovTribeMcpAdapter adapter = GovTribeMcpAdapter.dormant();
        assertFalse(adapter.available());
        assertTrue(adapter.searchOpportunities(SolicitationFilters.none()).isEmpty());
    }

    @Test
    void normalizesThroughAConfiguredBridge() throws Exception {
        GovTribeMcpAdapter.GovTribeBridge bridge = new GovTribeMcpAdapter.GovTribeBridge() {
            public boolean available() {
                return true;
            }

            public String searchOpportunities(String query, int limit) {
                return "{\"items\":[{\"id\":\"gt-1\",\"title\":\"Bridge Repair\","
                        + "\"agency\":\"USACE\",\"dueDate\":\"2026-09-01\","
                        + "\"url\":\"https://govtribe.com/opp/gt-1\"}]}";
            }
        };
        GovTribeMcpAdapter adapter = new GovTribeMcpAdapter(bridge);
        assertTrue(adapter.available());
        List<Solicitation> out = adapter.searchOpportunities(SolicitationFilters.none());
        assertEquals(1, out.size());
        assertEquals("govtribe", out.get(0).source());
        assertEquals("Bridge Repair", out.get(0).title());
        assertEquals("https://govtribe.com/opp/gt-1", out.get(0).sourceUrl());
    }
}
