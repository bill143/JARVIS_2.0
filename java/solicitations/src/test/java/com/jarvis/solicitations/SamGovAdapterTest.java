package com.jarvis.solicitations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SamGovAdapterTest {

    // A realistic SAM.gov Opportunities API v2 search response (shape per the public API docs).
    private static final String SAM_JSON = """
        {
          "totalRecords": 1,
          "opportunitiesData": [
            {
              "noticeId": "abc123def456",
              "title": "Roof Replacement, Building 5",
              "solicitationNumber": "36C25526R0081",
              "fullParentPathName": "VETERANS AFFAIRS, DEPARTMENT OF.VA MEDICAL CENTER",
              "postedDate": "2026-06-01",
              "type": "Solicitation",
              "baseType": "Presolicitation",
              "typeOfSetAside": "SDVOSBC",
              "typeOfSetAsideDescription": "Service-Disabled Veteran-Owned Small Business (SDVOSB) Set-Aside",
              "naicsCode": "236220",
              "responseDeadLine": "2026-08-01T17:00:00-04:00",
              "placeOfPerformance": {
                "city": {"code": "0", "name": "Richmond"},
                "state": {"code": "VA", "name": "Virginia"}
              },
              "uiLink": "https://sam.gov/opp/abc123def456/view",
              "resourceLinks": [
                "https://api.sam.gov/prod/opportunities/v3/abc123def456/resources/download/spec.pdf"
              ]
            }
          ]
        }
        """;

    @Test
    void normalizesSamResponseIntoCanonicalSchema() throws Exception {
        List<Solicitation> out = SamGovAdapter.normalize(
                new ObjectMapper().readTree(SAM_JSON), "2026-07-14T00:00:00Z");
        assertEquals(1, out.size());
        Solicitation s = out.get(0);
        assertEquals("sam.gov", s.source());
        assertEquals("abc123def456", s.sourceId());
        assertEquals("sam.gov:abc123def456", s.id());
        assertEquals("36C25526R0081", s.solicitationNumber());
        assertEquals("VETERANS AFFAIRS, DEPARTMENT OF", s.agency());
        assertEquals("VA MEDICAL CENTER", s.subAgency());
        assertTrue(s.setAside().contains("SDVOSB"));
        assertEquals(List.of("236220"), s.naics());
        assertEquals("VA", s.placeOfPerformance().state());
        assertEquals("Richmond", s.placeOfPerformance().city());
        assertEquals("2026-08-01T17:00:00-04:00", s.dueDate());
        assertEquals("Solicitation", s.status());
        // Attribution: source link is always present.
        assertEquals("https://sam.gov/opp/abc123def456/view", s.sourceUrl());
        // Documents come from resourceLinks, badged to the source.
        assertEquals(1, s.documents().size());
        assertEquals("sam.gov", s.documents().get(0).source());
        assertTrue(s.documents().get(0).url().endsWith("spec.pdf"));
    }

    @Test
    void dormantAdapterReturnsEmptyAndNeverThrows() throws Exception {
        SamGovAdapter adapter = new SamGovAdapter(new SamGovTransport() {
            public boolean available() {
                return false;
            }

            public String search(Map<String, String> params) {
                throw new AssertionError("must not touch the network when dormant");
            }
        });
        assertFalse(adapter.available());
        assertTrue(adapter.searchOpportunities(SolicitationFilters.none()).isEmpty());
        assertEquals(null, adapter.getOpportunityById("x"));
    }

    @Test
    void liveTransportSeamDrivesNormalization() throws Exception {
        SamGovAdapter adapter = new SamGovAdapter(new SamGovTransport() {
            public boolean available() {
                return true;
            }

            public String search(Map<String, String> params) {
                assertEquals("50", params.get("limit"));
                return SAM_JSON;
            }
        });
        List<Solicitation> out = adapter.searchOpportunities(SolicitationFilters.none());
        assertEquals(1, out.size());
        assertEquals("Roof Replacement, Building 5", out.get(0).title());
    }

    @Test
    void buildsNarrowingParamsFromFilters() {
        Map<String, String> p = SamGovAdapter.paramsFor(new SolicitationFilters(
                "SDVOSBC", null, null, List.of("236", "237"), DueWindow.ANY,
                null, "VA", null, null, "roof"));
        assertEquals("236", p.get("ncode"));
        assertEquals("VA", p.get("state"));
        assertEquals("SDVOSBC", p.get("typeOfSetAside"));
        assertEquals("roof", p.get("title"));
    }
}
