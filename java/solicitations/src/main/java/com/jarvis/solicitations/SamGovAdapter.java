package com.jarvis.solicitations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes the SAM.gov Opportunities API v2 into the canonical {@link Solicitation} schema. Query
 * params are built from {@link SolicitationFilters} to narrow the fetch; precise filtering is then
 * re-applied client-side by {@link SolicitationFilters#apply}, so results are correct even where SAM's
 * server-side filters are coarse.
 *
 * <p>The network lives entirely behind {@link SamGovTransport}, so {@link #normalize} is unit-tested
 * against a real captured response with no key and no network. Fields SAM omits from the search
 * response (full description text — a fetch URL only; amendment history; geocoordinates) are left
 * empty here and called out as v2 enrichment, never fabricated.
 */
public final class SamGovAdapter implements SolicitationSourceAdapter {

    public static final String SOURCE = "sam.gov";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SamGovTransport transport;

    public SamGovAdapter(SamGovTransport transport) {
        this.transport = transport;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public boolean available() {
        return transport != null && transport.available();
    }

    @Override
    public List<Solicitation> searchOpportunities(SolicitationFilters filters) throws SourceException {
        if (!available()) {
            return List.of();
        }
        try {
            String json = transport.search(paramsFor(filters));
            return normalize(MAPPER.readTree(json), Instant.now().toString());
        } catch (IOException | RuntimeException e) {
            throw new SourceException("SAM.gov search failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("SAM.gov search interrupted", e);
        }
    }

    @Override
    public Solicitation getOpportunityById(String sourceId) throws SourceException {
        if (!available() || sourceId == null || sourceId.isBlank()) {
            return null;
        }
        try {
            String json = transport.search(Map.of("noticeid", sourceId.strip(), "limit", "1"));
            List<Solicitation> found = normalize(MAPPER.readTree(json), Instant.now().toString());
            return found.isEmpty() ? null : found.get(0);
        } catch (IOException | RuntimeException e) {
            throw new SourceException("SAM.gov lookup failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("SAM.gov lookup interrupted", e);
        }
    }

    @Override
    public List<SolicitationDocument> getOpportunityDocuments(String sourceId) throws SourceException {
        Solicitation s = getOpportunityById(sourceId);
        return s == null ? List.of() : s.documents();
    }

    /** Builds SAM query params from filters (best-effort narrowing; precise filter re-applied later). */
    static Map<String, String> paramsFor(SolicitationFilters f) {
        Map<String, String> p = new LinkedHashMap<>();
        if (f != null) {
            if (!f.naicsPrefixes().isEmpty()) {
                p.put("ncode", f.naicsPrefixes().get(0).strip());
            }
            if (f.state() != null && !f.state().isBlank()) {
                p.put("state", f.state().strip());
            }
            if (f.setAside() != null && !f.setAside().isBlank()) {
                p.put("typeOfSetAside", f.setAside().strip());
            }
            if (f.query() != null && !f.query().isBlank()) {
                p.put("title", f.query().strip());
            }
        }
        p.put("limit", "50");
        return p;
    }

    /** Normalizes a SAM v2 search response body into canonical solicitations. Never throws. */
    static List<Solicitation> normalize(JsonNode root, String fetchedAt) {
        List<Solicitation> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonNode data = root.path("opportunitiesData");
        if (!data.isArray()) {
            return out;
        }
        for (JsonNode o : data) {
            String noticeId = text(o, "noticeId");
            if (noticeId.isBlank()) {
                continue;
            }
            String[] agency = splitAgency(o);
            out.add(new Solicitation(
                    null, SOURCE, noticeId,
                    text(o, "title"),
                    text(o, "solicitationNumber"),
                    agency[0], agency[1],
                    firstNonBlank(text(o, "typeOfSetAsideDescription"), text(o, "typeOfSetAside")),
                    naicsOf(o),
                    placeOf(o),
                    text(o, "postedDate"),
                    text(o, "responseDeadLine"),
                    awardAmount(o), awardAmount(o),
                    firstNonBlank(text(o, "type"), text(o, "baseType")),
                    "",                                  // full description is a fetch URL in SAM (v2 enrichment)
                    documentsOf(o),
                    List.of(),                           // amendment history: per-notice endpoint (v2 enrichment)
                    text(o, "uiLink"),
                    fetchedAt));
        }
        return out;
    }

    private static String[] splitAgency(JsonNode o) {
        String dept = text(o, "department");
        String sub = firstNonBlank(text(o, "subTier"), text(o, "office"));
        if (!dept.isBlank()) {
            return new String[] {dept, sub};
        }
        String full = text(o, "fullParentPathName");
        if (full.isBlank()) {
            return new String[] {"", sub};
        }
        String[] parts = full.split("\\.");
        String agency = parts[0].trim();
        String subAgency = parts.length > 1 ? parts[1].trim() : sub;
        return new String[] {agency, subAgency};
    }

    private static List<String> naicsOf(JsonNode o) {
        List<String> codes = new ArrayList<>();
        JsonNode arr = o.path("naicsCodes");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                if (!n.asText("").isBlank()) {
                    codes.add(n.asText().trim());
                }
            }
        }
        String single = text(o, "naicsCode");
        if (!single.isBlank() && !codes.contains(single)) {
            codes.add(single);
        }
        return codes;
    }

    private static PlaceOfPerformance placeOf(JsonNode o) {
        JsonNode pop = o.path("placeOfPerformance");
        if (pop.isMissingNode() || pop.isNull()) {
            return PlaceOfPerformance.unknown();
        }
        String city = pop.path("city").path("name").asText("");
        String state = pop.path("state").path("code").asText(
                pop.path("state").path("name").asText(""));
        return new PlaceOfPerformance(city, state, null, null);   // SAM omits coordinates
    }

    private static List<SolicitationDocument> documentsOf(JsonNode o) {
        List<SolicitationDocument> docs = new ArrayList<>();
        JsonNode links = o.path("resourceLinks");
        String posted = text(o, "postedDate");
        if (links.isArray()) {
            for (JsonNode link : links) {
                String url = link.asText("");
                if (url.isBlank()) {
                    continue;
                }
                String name = url.substring(url.lastIndexOf('/') + 1);
                docs.add(new SolicitationDocument(name.isBlank() ? "attachment" : name, url,
                        "", null, posted, SOURCE));
            }
        }
        return docs;
    }

    private static Long awardAmount(JsonNode o) {
        JsonNode award = o.path("award");
        if (award.isObject() && award.path("amount").isValueNode()) {
            try {
                String raw = award.path("amount").asText("").replaceAll("[,$]", "").trim();
                return raw.isBlank() ? null : (long) Double.parseDouble(raw);
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static String text(JsonNode o, String field) {
        JsonNode n = o.path(field);
        return n.isValueNode() ? n.asText("").trim() : "";
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b == null ? "" : b);
    }
}
