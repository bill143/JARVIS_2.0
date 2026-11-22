package com.jarvis.solicitations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SolicitationFiltersTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 14);

    private Solicitation sol(String id, String setAside, List<String> naics, String state,
            Long min, Long max, String status, String source, String dueDate) {
        return new Solicitation(null, source, id, "Title " + id, "SN-" + id, "VA", "office",
                setAside, naics, new PlaceOfPerformance("City", state, null, null),
                "2026-06-01", dueDate, min, max, status, "desc", List.of(), List.of(),
                "https://sam.gov/opp/" + id, "2026-07-14T00:00:00Z");
    }

    @Test
    void dueWindowBucketsByDaysUntilClose() {
        Solicitation soon = sol("a", "", List.of(), "VA", null, null, "Solicitation", "sam.gov",
                "2026-07-30");   // 16 days
        Solicitation mid = sol("b", "", List.of(), "VA", null, null, "Solicitation", "sam.gov",
                "2026-08-20");   // 37 days
        Solicitation far = sol("c", "", List.of(), "VA", null, null, "Solicitation", "sam.gov",
                "2026-10-01");   // 79 days

        assertTrue(new SolicitationFilters(null, null, null, List.of(), DueWindow.LT_28,
                null, null, null, null, null).matches(soon, TODAY));
        assertFalse(new SolicitationFilters(null, null, null, List.of(), DueWindow.LT_28,
                null, null, null, null, null).matches(mid, TODAY));
        assertTrue(new SolicitationFilters(null, null, null, List.of(), DueWindow.D28_45,
                null, null, null, null, null).matches(mid, TODAY));
        assertTrue(new SolicitationFilters(null, null, null, List.of(), DueWindow.GT_45,
                null, null, null, null, null).matches(far, TODAY));
    }

    @Test
    void unparseableDueDateOnlyMatchesAnyWindow() {
        Solicitation s = sol("x", "", List.of(), "VA", null, null, "Solicitation", "sam.gov", "");
        assertFalse(new SolicitationFilters(null, null, null, List.of(), DueWindow.LT_28,
                null, null, null, null, null).matches(s, TODAY));
        assertTrue(SolicitationFilters.none().matches(s, TODAY));
    }

    @Test
    void naicsPrefixMatchesConstructionFamilies() {
        Solicitation s = sol("n", "", List.of("236220"), "VA", null, null, "Solicitation",
                "sam.gov", "2026-08-01");
        assertTrue(new SolicitationFilters(null, null, null, List.of("236"), DueWindow.ANY,
                null, null, null, null, null).matches(s, TODAY));
        assertTrue(new SolicitationFilters(null, null, null, List.of("237", "238", "236"),
                DueWindow.ANY, null, null, null, null, null).matches(s, TODAY));
        assertFalse(new SolicitationFilters(null, null, null, List.of("541"), DueWindow.ANY,
                null, null, null, null, null).matches(s, TODAY));
    }

    @Test
    void setAsideMatchesExactlyIgnoringCase() {
        Solicitation s = sol("sa", "SDVOSB", List.of(), "VA", null, null, "Solicitation",
                "sam.gov", "2026-08-01");
        assertTrue(new SolicitationFilters("sdvosb", null, null, List.of(), DueWindow.ANY,
                null, null, null, null, null).matches(s, TODAY));
        assertFalse(new SolicitationFilters("8(a)", null, null, List.of(), DueWindow.ANY,
                null, null, null, null, null).matches(s, TODAY));
    }

    @Test
    void valueRangeOverlapsAndExcludesUnknownWhenBoundSet() {
        Solicitation inRange = sol("v1", "", List.of(), "VA", 2_000_000L, 5_000_000L,
                "Solicitation", "sam.gov", "2026-08-01");
        Solicitation below = sol("v2", "", List.of(), "VA", 100_000L, 500_000L,
                "Solicitation", "sam.gov", "2026-08-01");
        Solicitation unknown = sol("v3", "", List.of(), "VA", null, null,
                "Solicitation", "sam.gov", "2026-08-01");

        SolicitationFilters range = new SolicitationFilters(null, 1_500_000L, 13_500_000L,
                List.of(), DueWindow.ANY, null, null, null, null, null);
        assertTrue(range.matches(inRange, TODAY));
        assertFalse(range.matches(below, TODAY));
        assertFalse(range.matches(unknown, TODAY));       // unknown value excluded when a bound is set
        assertTrue(SolicitationFilters.none().matches(unknown, TODAY));   // but kept with no value filter
    }

    @Test
    void combinedFilterAppliesAcrossAList() {
        List<Solicitation> all = List.of(
                sol("1", "SDVOSB", List.of("236220"), "VA", 2_000_000L, 8_000_000L,
                        "Solicitation", "sam.gov", "2026-08-20"),   // matches everything
                sol("2", "SDVOSB", List.of("541330"), "VA", 2_000_000L, 8_000_000L,
                        "Solicitation", "sam.gov", "2026-08-20"),   // wrong NAICS
                sol("3", "8(a)", List.of("237310"), "TX", 2_000_000L, 8_000_000L,
                        "Solicitation", "sam.gov", "2026-08-20"));  // wrong set-aside + state

        SolicitationFilters f = new SolicitationFilters("SDVOSB", 1_500_000L, 13_500_000L,
                List.of("236", "237", "238"), DueWindow.D28_45, null, "VA", null, "sam.gov", null);
        List<Solicitation> hits = f.apply(all, TODAY);
        assertEquals(1, hits.size());
        assertEquals("sam.gov:1", hits.get(0).id());
    }

    @Test
    void parsesIsoOffsetDueDates() {
        assertEquals(LocalDate.of(2026, 8, 1),
                SolicitationFilters.parseDate("2026-08-01T17:00:00-04:00"));
        assertEquals(LocalDate.of(2026, 8, 1), SolicitationFilters.parseDate("2026-08-01"));
        assertEquals(null, SolicitationFilters.parseDate("not a date"));
    }
}
