package com.jarvis.solicitations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MapPayloadTest {

    private Solicitation withPlace(String id, String state, Double lat, Double lng) {
        return new Solicitation(null, "sam.gov", id, "T" + id, "SN" + id, "VA", "", "",
                List.of(), new PlaceOfPerformance("City", state, lat, lng),
                "2026-06-01", "2026-08-01", null, null, "Solicitation", "", List.of(), List.of(),
                "https://sam.gov/opp/" + id, "2026-07-14T00:00:00Z");
    }

    @Test
    void plotsOnlyCoordinatedRecordsAndCountsTheRest() {
        MapPayload m = MapPayload.build(List.of(
                withPlace("1", "VA", 37.5, -77.4),
                withPlace("2", "VA", null, null),      // no coords → unplotted
                withPlace("3", "TX", 30.3, -97.7)));
        assertEquals(2, m.plotted());
        assertEquals(1, m.unplotted());
        assertEquals(2, m.points().size());
        assertTrue(m.points().stream().allMatch(p -> p.lat() != 0.0));
    }

    @Test
    void groupsByStateSortedByCountDescending() {
        MapPayload m = MapPayload.build(List.of(
                withPlace("1", "VA", null, null),
                withPlace("2", "VA", null, null),
                withPlace("3", "TX", null, null)));
        assertEquals("VA", m.groups().get(0).state());
        assertEquals(2, m.groups().get(0).count());
        assertEquals("TX", m.groups().get(1).state());
    }

    @Test
    void nullAndBlankStatesAreHandled() {
        MapPayload m = MapPayload.build(List.of(withPlace("1", "", null, null)));
        assertEquals(1, m.groups().size());
        assertEquals("??", m.groups().get(0).state());
        assertEquals(0, m.plotted());
    }
}
