package com.jarvis.app;

import com.jarvis.audit.AuditTrigger;
import com.jarvis.integrations.Plugin;
import com.jarvis.integrations.PluginDescriptor;
import com.jarvis.solicitations.DueWindow;
import com.jarvis.solicitations.Solicitation;
import com.jarvis.solicitations.SolicitationDocument;
import com.jarvis.solicitations.SolicitationFilters;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * AI tools over the Solicitations Command Center (READ_ONLY, manifest-tiered). Lets the assistant
 * answer questions like "show SDVOSB VA jobs between $1.5M and $13.5M closing in 30 days" or "list
 * docs for solicitation 36C25526R0081". <b>Every result line carries its source URL</b>, so the
 * assistant's answer is always attributable — satisfying the citation requirement. On any failure the
 * tool returns a failed {@link ToolResult}, never a throw.
 */
final class SolicitationsPlugin implements Plugin {

    private final SolicitationsService service;

    SolicitationsPlugin(SolicitationsService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("solicitations", "0.1.0",
                "Federal solicitations search over SAM.gov / GovTribe with source-cited results");
    }

    @Override
    public List<Tool> tools() {
        return List.of(search(), documents());
    }

    private Tool search() {
        return new Tool() {
            public String name() {
                return "solicitations_search";
            }

            public String description() {
                return "Search federal solicitations with filters (setAside, state, naics prefixes, "
                        + "valueMin/valueMax, due window <28d/28-45d/45+d, query). Returns cited results.";
            }

            public ToolResult execute(ToolCall call) {
                if (!service.anySourceAvailable()) {
                    return ToolResult.error("No solicitation source is configured — set SAMGOV_API_KEY "
                            + "(or wire a GovTribe bridge) to enable live search.");
                }
                SolicitationFilters filters = filtersFrom(call);
                service.refresh(filters, AuditTrigger.AUTONOMOUS);
                List<Solicitation> hits = service.list(filters);
                int max = intArg(call, "max", 10);
                return ToolResult.ok(formatResults(hits, max));
            }
        };
    }

    private Tool documents() {
        return new Tool() {
            public String name() {
                return "solicitations_documents";
            }

            public String description() {
                return "List documents/attachments for a solicitation id or number. Returns cited links.";
            }

            public ToolResult execute(ToolCall call) {
                String id = strArg(call, "id", "");
                if (id.isBlank()) {
                    return ToolResult.error("id is required");
                }
                Solicitation match = service.list(SolicitationFilters.none()).stream()
                        .filter(s -> s.id().equalsIgnoreCase(id)
                                || s.solicitationNumber().equalsIgnoreCase(id))
                        .findFirst().orElse(null);
                if (match == null) {
                    return ToolResult.error("No cached solicitation matches '" + id
                            + "' — run a search first so it is loaded.");
                }
                List<SolicitationDocument> docs = service.documents(match.id());
                StringBuilder sb = new StringBuilder("Documents for ")
                        .append(match.solicitationNumber().isBlank() ? match.id()
                                : match.solicitationNumber())
                        .append(" (source: ").append(match.sourceUrl()).append("):\n");
                if (docs.isEmpty()) {
                    sb.append("  (none provided by the source)");
                }
                for (SolicitationDocument d : docs) {
                    sb.append("  - ").append(d.name()).append(" [").append(d.source()).append("] ")
                            .append(d.url()).append('\n');
                }
                return ToolResult.ok(sb.toString().strip());
            }
        };
    }

    private SolicitationFilters filtersFrom(ToolCall call) {
        List<String> naics = new ArrayList<>();
        String naicsArg = strArg(call, "naics", "");
        if (!naicsArg.isBlank()) {
            naics = Arrays.stream(naicsArg.split(",")).map(String::strip)
                    .filter(s -> !s.isBlank()).toList();
        }
        return new SolicitationFilters(
                blankToNull(strArg(call, "setAside", "")),
                longArg(call, "valueMin"), longArg(call, "valueMax"),
                naics, DueWindow.parse(strArg(call, "due", "")),
                blankToNull(strArg(call, "agency", "")),
                blankToNull(strArg(call, "state", "")),
                blankToNull(strArg(call, "status", "")),
                blankToNull(strArg(call, "source", "")),
                blankToNull(strArg(call, "query", "")));
    }

    private static String formatResults(List<Solicitation> hits, int max) {
        if (hits.isEmpty()) {
            return "No matching solicitations.";
        }
        StringBuilder sb = new StringBuilder("Found ").append(hits.size())
                .append(" matching solicitations (showing up to ").append(max).append("):\n");
        int n = 0;
        for (Solicitation s : hits) {
            if (n++ >= max) {
                break;
            }
            sb.append("\n").append(n).append(". ").append(s.title())
                    .append(" — ").append(s.agency());
            if (!s.setAside().isBlank()) {
                sb.append(" · set-aside: ").append(s.setAside());
            }
            if (s.estValueMin() != null || s.estValueMax() != null) {
                sb.append(" · value: ").append(s.estValueMin()).append("–").append(s.estValueMax());
            }
            if (!s.dueDate().isBlank()) {
                sb.append(" · due: ").append(s.dueDate());
            }
            sb.append("\n   solicitation #: ").append(s.solicitationNumber())
                    .append("  |  source: ").append(s.sourceUrl());   // citation
        }
        return sb.toString();
    }

    private static String strArg(ToolCall call, String key, String dflt) {
        Object v = call.arguments().get(key);
        return v == null ? dflt : v.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static int intArg(ToolCall call, String key, int dflt) {
        Object v = call.arguments().get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? dflt : Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static Long longArg(ToolCall call, String key) {
        Object v = call.arguments().get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null || v.toString().isBlank() ? null : Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
