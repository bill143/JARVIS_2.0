package com.jarvis.solicitations;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The operator's filter selection, and the pure logic that applies it. Every field is optional — a
 * null/blank string or empty list means "no constraint on this dimension". The logic is
 * deterministic and side-effect free (a {@code today} reference is passed in), so it is exhaustively
 * unit-tested without a clock or network.
 *
 * <p>Documented edge choices:
 * <ul>
 *   <li><b>Value range:</b> a solicitation matches if its {@code [estValueMin, estValueMax]} overlaps
 *       the requested range (nulls treated as open on that side). A solicitation with <em>no</em>
 *       value information is excluded only when a value bound is actually set — because it cannot be
 *       confirmed in range.</li>
 *   <li><b>Due window:</b> a solicitation whose due date is blank/unparseable matches only
 *       {@link DueWindow#ANY}.</li>
 *   <li><b>NAICS prefix:</b> matches if any of the solicitation's codes starts with any requested
 *       prefix (e.g. {@code 236}, {@code 237}, {@code 238}).</li>
 * </ul>
 */
public record SolicitationFilters(
        String setAside,
        Long valueMin,
        Long valueMax,
        List<String> naicsPrefixes,
        DueWindow dueWindow,
        String agency,
        String state,
        String status,
        String source,
        String query) {

    public SolicitationFilters {
        naicsPrefixes = naicsPrefixes == null ? List.of() : List.copyOf(naicsPrefixes);
        dueWindow = dueWindow == null ? DueWindow.ANY : dueWindow;
    }

    /** An empty filter that matches everything. */
    public static SolicitationFilters none() {
        return new SolicitationFilters(null, null, null, List.of(), DueWindow.ANY,
                null, null, null, null, null);
    }

    /** Applies this filter to {@code all}, preserving order. */
    public List<Solicitation> apply(List<Solicitation> all, LocalDate today) {
        List<Solicitation> out = new ArrayList<>();
        for (Solicitation s : all) {
            if (matches(s, today)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Whether one solicitation satisfies every active constraint. */
    public boolean matches(Solicitation s, LocalDate today) {
        if (set(setAside) && !eq(s.setAside(), setAside)) {
            return false;
        }
        if (set(agency) && !contains(s.agency(), agency) && !contains(s.subAgency(), agency)) {
            return false;
        }
        if (set(state) && !eq(s.placeOfPerformance().state(), state)) {
            return false;
        }
        if (set(status) && !eq(s.status(), status)) {
            return false;
        }
        if (set(source) && !eq(s.source(), source)) {
            return false;
        }
        if (!naicsPrefixes.isEmpty() && !naicsMatches(s)) {
            return false;
        }
        if ((valueMin != null || valueMax != null) && !valueOverlaps(s)) {
            return false;
        }
        if (dueWindow != DueWindow.ANY && !dueWindow.contains(daysUntilDue(s, today))) {
            return false;
        }
        if (set(query) && !textMatches(s, query)) {
            return false;
        }
        return true;
    }

    private boolean naicsMatches(Solicitation s) {
        for (String code : s.naics()) {
            for (String prefix : naicsPrefixes) {
                if (prefix != null && !prefix.isBlank() && code.startsWith(prefix.strip())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean valueOverlaps(Solicitation s) {
        Long lo = s.estValueMin();
        Long hi = s.estValueMax();
        if (lo == null && hi == null) {
            return false;   // unknown value can't be confirmed in the requested range
        }
        long sLo = lo != null ? lo : Long.MIN_VALUE;
        long sHi = hi != null ? hi : Long.MAX_VALUE;
        long fLo = valueMin != null ? valueMin : Long.MIN_VALUE;
        long fHi = valueMax != null ? valueMax : Long.MAX_VALUE;
        return sLo <= fHi && sHi >= fLo;
    }

    /** Days from {@code today} to the due date; {@link Long#MIN_VALUE} when unparseable. */
    static long daysUntilDue(Solicitation s, LocalDate today) {
        LocalDate due = parseDate(s.dueDate());
        if (due == null) {
            return Long.MIN_VALUE;
        }
        return ChronoUnit.DAYS.between(today, due);
    }

    /** Accepts {@code yyyy-MM-dd} and ISO offset date-times (e.g. SAM's {@code 2026-08-01T17:00:00-04:00}). */
    static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        try {
            if (v.length() >= 10 && v.charAt(4) == '-' && (v.length() == 10 || v.charAt(10) == 'T')) {
                if (v.length() == 10) {
                    return LocalDate.parse(v);
                }
                return OffsetDateTime.parse(v).toLocalDate();
            }
            return LocalDate.parse(v.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean textMatches(Solicitation s, String q) {
        String needle = q.toLowerCase(Locale.ROOT);
        return contains(s.title(), needle) || contains(s.description(), needle)
                || contains(s.solicitationNumber(), needle) || contains(s.agency(), needle);
    }

    private static boolean set(String v) {
        return v != null && !v.isBlank();
    }

    private static boolean eq(String actual, String wanted) {
        return actual != null && actual.equalsIgnoreCase(wanted.strip());
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null
                && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
