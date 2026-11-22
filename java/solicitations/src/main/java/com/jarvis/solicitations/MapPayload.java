package com.jarvis.solicitations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The map view model: plottable points (only solicitations that carry coordinates), per-state group
 * counts, and how many records had no coordinate to plot — surfaced so the UI can honestly say
 * "N of M shown on map" instead of silently dropping the rest.
 */
public record MapPayload(List<MapPoint> points, List<StateGroup> groups, int plotted, int unplotted) {

    /** One plotted solicitation. */
    public record MapPoint(String id, String title, double lat, double lng, String state,
            String dueDate, String source) {
    }

    /** A state and how many solicitations (plotted or not) name it as place of performance. */
    public record StateGroup(String state, int count) {
    }

    /** Builds the map payload from canonical solicitations. Never throws; null-safe. */
    public static MapPayload build(List<Solicitation> solicitations) {
        List<MapPoint> points = new ArrayList<>();
        Map<String, Integer> byState = new LinkedHashMap<>();
        int unplotted = 0;
        if (solicitations != null) {
            for (Solicitation s : solicitations) {
                PlaceOfPerformance pop = s.placeOfPerformance();
                String state = pop.state().isBlank() ? "??" : pop.state().toUpperCase();
                byState.merge(state, 1, Integer::sum);
                if (pop.hasCoordinates()) {
                    points.add(new MapPoint(s.id(), s.title(), pop.lat(), pop.lng(),
                            pop.state(), s.dueDate(), s.source()));
                } else {
                    unplotted++;
                }
            }
        }
        List<StateGroup> groups = new ArrayList<>();
        byState.forEach((st, count) -> groups.add(new StateGroup(st, count)));
        groups.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return new MapPayload(points, groups, points.size(), unplotted);
    }
}
