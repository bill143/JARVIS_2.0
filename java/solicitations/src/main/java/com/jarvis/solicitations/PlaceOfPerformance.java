package com.jarvis.solicitations;

/**
 * Where the work is performed. {@code lat}/{@code lng} are {@link Double} so they can be absent
 * (many notices give only city/state, or nothing) — a null coordinate means "not geocoded" and the
 * map view simply omits the point rather than plotting (0,0).
 */
public record PlaceOfPerformance(String city, String state, Double lat, Double lng) {

    public PlaceOfPerformance {
        city = city == null ? "" : city.strip();
        state = state == null ? "" : state.strip();
    }

    /** True when a coordinate pair is present and plottable on the map. */
    public boolean hasCoordinates() {
        return lat != null && lng != null;
    }

    public static PlaceOfPerformance unknown() {
        return new PlaceOfPerformance("", "", null, null);
    }
}
