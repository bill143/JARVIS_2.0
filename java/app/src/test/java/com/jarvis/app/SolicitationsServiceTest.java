package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.audit.AuditEntry;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditQuery;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.audit.RecordStoreAuditLog;
import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.solicitations.DocumentConnector;
import com.jarvis.solicitations.PlaceOfPerformance;
import com.jarvis.solicitations.Solicitation;
import com.jarvis.solicitations.SolicitationDocument;
import com.jarvis.solicitations.SolicitationFilters;
import com.jarvis.solicitations.SolicitationSourceAdapter;
import java.util.List;
import org.junit.jupiter.api.Test;

class SolicitationsServiceTest {

    private Solicitation sample() {
        return new Solicitation(null, "sam.gov", "n1", "Roof Replacement", "36C25526R0081",
                "VA", "VAMC", "SDVOSB", List.of("236220"),
                new PlaceOfPerformance("Richmond", "VA", 37.5, -77.4),
                "2026-06-01", "2026-08-01", 2_000_000L, 8_000_000L, "Solicitation", "desc",
                List.of(new SolicitationDocument("spec.pdf", "https://sam.gov/x/spec.pdf", "pdf",
                        null, "2026-06-01", "sam.gov")),
                List.of(), "https://sam.gov/opp/n1", "2026-07-14T00:00:00Z");
    }

    private SolicitationSourceAdapter fakeSource(boolean available) {
        return new SolicitationSourceAdapter() {
            public String source() {
                return "sam.gov";
            }

            public boolean available() {
                return available;
            }

            public List<Solicitation> searchOpportunities(SolicitationFilters f) {
                return List.of(sample());
            }

            public Solicitation getOpportunityById(String id) {
                return sample();
            }

            public List<SolicitationDocument> getOpportunityDocuments(String id) {
                return sample().documents();
            }
        };
    }

    @Test
    void refreshQueriesSourcesCachesAndAuditsWithCorrelationId() {
        AuditLog audit = new RecordStoreAuditLog(new InMemoryRecordStore());
        SolicitationsService svc = new SolicitationsService(
                List.of(fakeSource(true)), List.of(), audit);
        assertTrue(svc.anySourceAvailable());

        SolicitationsService.RefreshResult r =
                svc.refresh(SolicitationFilters.none(), AuditTrigger.USER);
        assertEquals(1, r.total());
        assertEquals(1, (int) r.perSource().get("sam.gov"));
        assertTrue(r.correlationId().startsWith("sol-"));

        // Audit trail carries the source query + the refresh, tagged with the correlation id.
        List<AuditEntry> entries = audit.query(AuditQuery.all());
        assertTrue(entries.stream().anyMatch(e -> e.event().action().equals("solicitations_source_query")
                && e.event().detail().contains(r.correlationId())));
        assertTrue(entries.stream().anyMatch(e -> e.event().action().equals("solicitations_refresh")));
    }

    @Test
    void listAppliesFiltersAndDetailIncludesDocsAndSourceUrl() {
        SolicitationsService svc = new SolicitationsService(
                List.of(fakeSource(true)), List.of(), null);
        svc.refresh(SolicitationFilters.none(), AuditTrigger.USER);

        // Matching filter keeps it; non-matching set-aside removes it.
        assertEquals(1, svc.list(new SolicitationFilters("SDVOSB", null, null, List.of("236"),
                com.jarvis.solicitations.DueWindow.ANY, null, "VA", null, null, null)).size());
        assertTrue(svc.list(new SolicitationFilters("8(a)", null, null, List.of(),
                com.jarvis.solicitations.DueWindow.ANY, null, null, null, null, null)).isEmpty());

        Solicitation got = svc.get("sam.gov:n1").orElseThrow();
        assertEquals("https://sam.gov/opp/n1", got.sourceUrl());
        assertEquals(1, svc.documents("sam.gov:n1").size());
    }

    @Test
    void mapPayloadPlotsCoordinatedRecords() {
        SolicitationsService svc = new SolicitationsService(
                List.of(fakeSource(true)), List.of(), null);
        svc.refresh(SolicitationFilters.none(), AuditTrigger.USER);
        assertEquals(1, svc.map(SolicitationFilters.none()).plotted());
    }

    @Test
    void dormantSourceYieldsEmptyAndNoCrash() {
        SolicitationsService svc = new SolicitationsService(
                List.of(fakeSource(false)), List.of(), null);
        assertFalse(svc.anySourceAvailable());
        svc.refresh(SolicitationFilters.none(), AuditTrigger.USER);
        assertTrue(svc.list(SolicitationFilters.none()).isEmpty());
    }

    @Test
    void documentConnectorsAreReadOnlyByType() {
        // Structural guarantee: the connector contract exposes only read methods. This test documents
        // that and verifies a dormant connector reports unavailable with a clear health string.
        for (java.lang.reflect.Method m : DocumentConnector.class.getMethods()) {
            String n = m.getName().toLowerCase();
            assertFalse(n.startsWith("write") || n.startsWith("delete") || n.startsWith("update")
                    || n.startsWith("upload") || n.startsWith("move"),
                    "connector must expose no mutation method, found: " + m.getName());
        }
        GoogleDriveConnector drive = GoogleDriveConnector.fromEnvironment(null);
        assertFalse(drive.available());
        assertTrue(drive.health().toLowerCase().contains("not configured")
                || drive.health().toLowerCase().contains("not authorized"));
    }

    @Test
    void aiSearchToolReturnsSourceCitedResults() {
        SolicitationsService svc = new SolicitationsService(
                List.of(fakeSource(true)), List.of(), null);
        SolicitationsPlugin plugin = new SolicitationsPlugin(svc);
        var tool = plugin.tools().stream()
                .filter(t -> t.name().equals("solicitations_search")).findFirst().orElseThrow();

        var result = tool.execute(new com.jarvis.tools.ToolCall("solicitations_search",
                java.util.Map.of("setAside", "SDVOSB", "naics", "236", "state", "VA")));
        assertTrue(result.success());
        assertTrue(result.output().contains("Roof Replacement"));
        assertTrue(result.output().contains("https://sam.gov/opp/n1"));   // citation present
    }

    @Test
    void aiToolIsReadOnlyAndDeclinesWhenNoSourceConfigured() {
        SolicitationsService svc = new SolicitationsService(
                List.of(fakeSource(false)), List.of(), null);
        var tool = new SolicitationsPlugin(svc).tools().stream()
                .filter(t -> t.name().equals("solicitations_search")).findFirst().orElseThrow();
        var result = tool.execute(new com.jarvis.tools.ToolCall("solicitations_search",
                java.util.Map.of()));
        assertFalse(result.success());
        assertTrue(result.error().toLowerCase().contains("configured"));
    }
}
