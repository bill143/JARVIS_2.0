package com.jarvis.solicitations;

import java.util.List;

/**
 * The canonical, source-agnostic solicitation record every adapter normalizes into. Nullable numeric
 * fields ({@code estValueMin/Max}) are {@link Long} so "unknown" is distinct from "zero". Lists are
 * never null (empty instead), so callers never guard for null collections.
 *
 * <p>{@code source} + {@code sourceId} together identify the origin record; {@code id} is the stable
 * internal key ({@code source + ":" + sourceId}). {@code sourceUrl} is the human-facing link back to
 * the originating system — required for the attribution guarantee (every shown fact is sourced).
 */
public record Solicitation(
        String id,
        String source,
        String sourceId,
        String title,
        String solicitationNumber,
        String agency,
        String subAgency,
        String setAside,
        List<String> naics,
        PlaceOfPerformance placeOfPerformance,
        String postedDate,
        String dueDate,
        Long estValueMin,
        Long estValueMax,
        String status,
        String description,
        List<SolicitationDocument> documents,
        List<Amendment> amendments,
        String sourceUrl,
        String fetchedAt) {

    public Solicitation {
        source = source == null ? "" : source.strip();
        sourceId = sourceId == null ? "" : sourceId.strip();
        id = (id == null || id.isBlank()) ? (source + ":" + sourceId) : id.strip();
        title = title == null ? "" : title.strip();
        solicitationNumber = solicitationNumber == null ? "" : solicitationNumber.strip();
        agency = agency == null ? "" : agency.strip();
        subAgency = subAgency == null ? "" : subAgency.strip();
        setAside = setAside == null ? "" : setAside.strip();
        naics = naics == null ? List.of() : List.copyOf(naics);
        placeOfPerformance = placeOfPerformance == null
                ? PlaceOfPerformance.unknown() : placeOfPerformance;
        postedDate = postedDate == null ? "" : postedDate.strip();
        dueDate = dueDate == null ? "" : dueDate.strip();
        status = status == null ? "" : status.strip();
        description = description == null ? "" : description;
        documents = documents == null ? List.of() : List.copyOf(documents);
        amendments = amendments == null ? List.of() : List.copyOf(amendments);
        sourceUrl = sourceUrl == null ? "" : sourceUrl.strip();
        fetchedAt = fetchedAt == null ? "" : fetchedAt.strip();
    }
}
