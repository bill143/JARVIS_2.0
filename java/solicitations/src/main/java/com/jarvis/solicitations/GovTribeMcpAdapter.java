package com.jarvis.solicitations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * GovTribe source adapter routed through an <b>MCP bridge seam</b>.
 *
 * <p><b>Architectural honesty:</b> the deployed JARVIS runtime has no MCP client of its own — GovTribe
 * MCP tools live in the operator's assistant/agent session, not in this JVM. So this adapter is
 * <b>dormant by default</b>: without a {@link GovTribeBridge} wired in, {@link #available()} is
 * {@code false} and searches return empty (never fabricated). When a bridge <em>is</em> provided
 * (e.g. an MCP-over-HTTP proxy the operator runs), it returns GovTribe opportunity JSON which this
 * adapter normalizes into the canonical schema. No GovTribe write actions are exposed.
 */
public final class GovTribeMcpAdapter implements SolicitationSourceAdapter {

    public static final String SOURCE = "govtribe";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Seam to an MCP bridge: takes a query and returns opportunity JSON, or is unavailable. */
    public interface GovTribeBridge {
        boolean available();

        /** Returns a JSON array (or {@code {items:[...]}}) of GovTribe opportunities. */
        String searchOpportunities(String query, int limit) throws Exception;
    }

    private final GovTribeBridge bridge;   // null → permanently dormant

    public GovTribeMcpAdapter(GovTribeBridge bridge) {
        this.bridge = bridge;
    }

    /** A dormant adapter (no bridge configured) — the default in a stock deployment. */
    public static GovTribeMcpAdapter dormant() {
        return new GovTribeMcpAdapter(null);
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public boolean available() {
        return bridge != null && bridge.available();
    }

    @Override
    public List<Solicitation> searchOpportunities(SolicitationFilters filters) throws SourceException {
        if (!available()) {
            return List.of();
        }
        try {
            String q = filters == null || filters.query() == null ? "" : filters.query();
            String json = bridge.searchOpportunities(q, 50);
            return normalize(MAPPER.readTree(json), java.time.Instant.now().toString());
        } catch (Exception e) {
            throw new SourceException("GovTribe search failed", e);
        }
    }

    @Override
    public Solicitation getOpportunityById(String sourceId) throws SourceException {
        return null;   // per-id lookup arrives with a concrete bridge (v2)
    }

    @Override
    public List<SolicitationDocument> getOpportunityDocuments(String sourceId) {
        return List.of();
    }

    /** Normalizes GovTribe opportunity JSON into canonical solicitations. Tolerant of shape. */
    static List<Solicitation> normalize(JsonNode root, String fetchedAt) {
        List<Solicitation> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonNode items = root.isArray() ? root : root.path("items");
        if (!items.isArray()) {
            return out;
        }
        for (JsonNode o : items) {
            String id = firstText(o, "id", "govtribeId", "piid");
            if (id.isBlank()) {
                continue;
            }
            out.add(new Solicitation(
                    null, SOURCE, id,
                    firstText(o, "title", "name"),
                    firstText(o, "solicitationNumber", "number"),
                    firstText(o, "agency", "department"),
                    firstText(o, "subAgency", "office"),
                    firstText(o, "setAside", "typeOfSetAside"),
                    List.of(),
                    PlaceOfPerformance.unknown(),
                    firstText(o, "postedDate", "posted"),
                    firstText(o, "dueDate", "responseDate", "closeDate"),
                    null, null,
                    firstText(o, "status", "type"),
                    firstText(o, "description", "summary"),
                    List.of(), List.of(),
                    firstText(o, "url", "sourceUrl", "link"),
                    fetchedAt));
        }
        return out;
    }

    private static String firstText(JsonNode o, String... fields) {
        for (String f : fields) {
            JsonNode n = o.path(f);
            if (n.isValueNode() && !n.asText("").isBlank()) {
                return n.asText().trim();
            }
        }
        return "";
    }
}
