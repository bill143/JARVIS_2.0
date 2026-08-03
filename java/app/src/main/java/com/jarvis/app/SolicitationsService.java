package com.jarvis.app;

import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.solicitations.DocumentConnector;
import com.jarvis.solicitations.DocumentRef;
import com.jarvis.solicitations.MapPayload;
import com.jarvis.solicitations.Solicitation;
import com.jarvis.solicitations.SolicitationDocument;
import com.jarvis.solicitations.SolicitationFilters;
import com.jarvis.solicitations.SolicitationSourceAdapter;
import com.jarvis.tools.RiskTier;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Solicitations Command Center service: fans a search across the configured source adapters,
 * normalizes into the canonical schema, caches the result, and serves list/detail/map/document views
 * over it. Every externally-observable action — a source query, a refresh, opening a solicitation, or
 * opening a document — is written to the {@link AuditLog} with a correlation id, satisfying the audit
 * requirement. All document access is read-only (the {@link DocumentConnector} contract exposes no
 * mutation).
 *
 * <p>The cache is refreshed only on an explicit {@link #refresh} (operator- or AI-initiated) — there
 * is no autonomous background polling, per the v1 non-goals.
 */
final class SolicitationsService {

    private final List<SolicitationSourceAdapter> sources;
    private final List<DocumentConnector> connectors;
    private final AuditLog audit;   // nullable
    private final AtomicLong correlation = new AtomicLong();

    private volatile List<Solicitation> cache = List.of();
    private volatile String lastRefresh = "";

    SolicitationsService(List<SolicitationSourceAdapter> sources,
            List<DocumentConnector> connectors, AuditLog audit) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.connectors = connectors == null ? List.of() : List.copyOf(connectors);
        this.audit = audit;
    }

    /** The refresh outcome: how many records each source returned. */
    record RefreshResult(String correlationId, int total, Map<String, Integer> perSource,
            List<String> errors) {
    }

    /** Whether any source or connector is live (drives the UI "configure me" state). */
    boolean anySourceAvailable() {
        return sources.stream().anyMatch(SolicitationSourceAdapter::available);
    }

    /** Queries every available source, merges + de-dupes into the cache. Audited per source. */
    synchronized RefreshResult refresh(SolicitationFilters filters, AuditTrigger trigger) {
        String cid = "sol-" + correlation.incrementAndGet();
        Map<String, Solicitation> merged = new LinkedHashMap<>();
        Map<String, Integer> perSource = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (SolicitationSourceAdapter adapter : sources) {
            if (!adapter.available()) {
                perSource.put(adapter.source(), 0);
                continue;
            }
            try {
                List<Solicitation> found = adapter.searchOpportunities(filters);
                for (Solicitation s : found) {
                    merged.putIfAbsent(s.id(), s);
                }
                perSource.put(adapter.source(), found.size());
                record("solicitations_source_query", trigger, AuditOutcome.SUCCESS,
                        cid + " source=" + adapter.source() + " results=" + found.size());
            } catch (SolicitationSourceAdapter.SourceException e) {
                perSource.put(adapter.source(), 0);
                errors.add(adapter.source() + ": " + safe(e.getMessage()));
                record("solicitations_source_query", trigger, AuditOutcome.FAILURE,
                        cid + " source=" + adapter.source() + " error");
            }
        }
        cache = new ArrayList<>(merged.values());
        lastRefresh = java.time.Instant.now().toString();
        record("solicitations_refresh", trigger, AuditOutcome.SUCCESS,
                cid + " total=" + cache.size());
        return new RefreshResult(cid, cache.size(), perSource, errors);
    }

    /** Cached solicitations narrowed by {@code filters}. Not audited (read of local cache). */
    List<Solicitation> list(SolicitationFilters filters) {
        SolicitationFilters f = filters == null ? SolicitationFilters.none() : filters;
        return f.apply(cache, LocalDate.now());
    }

    /** One solicitation by id (from cache). Audited as an "opened" event. */
    Optional<Solicitation> get(String id) {
        Optional<Solicitation> found = cache.stream().filter(s -> s.id().equals(id)).findFirst();
        record("solicitation_opened", AuditTrigger.USER,
                found.isPresent() ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE, "id=" + safe(id));
        return found;
    }

    /** Documents for a solicitation. Audited as a "document opened" event. */
    List<SolicitationDocument> documents(String id) {
        Optional<Solicitation> s = cache.stream().filter(x -> x.id().equals(id)).findFirst();
        record("solicitation_document_opened", AuditTrigger.USER, AuditOutcome.SUCCESS,
                "id=" + safe(id) + " docs=" + s.map(x -> x.documents().size()).orElse(0));
        return s.map(Solicitation::documents).orElse(List.of());
    }

    /** Map view model over the filtered cache. */
    MapPayload map(SolicitationFilters filters) {
        return MapPayload.build(list(filters));
    }

    /** Health of each configured document connector, for the UI/API. */
    List<Map<String, Object>> connectorStatus() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DocumentConnector c : connectors) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("available", c.available());
            m.put("health", c.health());
            out.add(m);
        }
        return out;
    }

    /** Read-only search across document connectors (e.g. Drive files for a solicitation number). */
    List<DocumentRef> searchDocuments(String query, int max) {
        List<DocumentRef> out = new ArrayList<>();
        for (DocumentConnector c : connectors) {
            if (!c.available()) {
                continue;
            }
            try {
                out.addAll(c.search(query, max));
                record("solicitations_document_search", AuditTrigger.USER, AuditOutcome.SUCCESS,
                        "connector=" + c.name() + " q=" + safe(query));
            } catch (DocumentConnector.ConnectorException e) {
                record("solicitations_document_search", AuditTrigger.USER, AuditOutcome.FAILURE,
                        "connector=" + c.name() + " error");
            }
        }
        return out;
    }

    String lastRefresh() {
        return lastRefresh;
    }

    List<SolicitationSourceAdapter> sources() {
        return sources;
    }

    private void record(String action, AuditTrigger trigger, AuditOutcome outcome, String detail) {
        if (audit == null) {
            return;
        }
        audit.record(new AuditEvent(AuditCategory.EXTERNAL_API, action, trigger,
                RiskTier.READ_ONLY, outcome, detail));
    }

    private static String safe(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]", " ");
    }
}
