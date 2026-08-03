package com.jarvis.solicitations;

import java.util.List;

/**
 * A source of solicitations (SAM.gov, GovTribe, …) normalized into the canonical {@link Solicitation}
 * schema. Implementations are <b>dormant-by-default</b>: when unconfigured, {@link #available()} is
 * {@code false} and the search/get methods return empty rather than throwing, so an operator with no
 * keys still gets a working (empty) UI.
 */
public interface SolicitationSourceAdapter {

    /** Stable source name stamped onto every record (e.g. {@code sam.gov}, {@code govtribe}). */
    String source();

    /** Whether this adapter is configured to make live calls (a key/bridge is present). */
    boolean available();

    /** Searches opportunities matching {@code filters}. Returns empty when unavailable. */
    List<Solicitation> searchOpportunities(SolicitationFilters filters) throws SourceException;

    /** Fetches one opportunity by its source id, or {@code null} if not found/unavailable. */
    Solicitation getOpportunityById(String sourceId) throws SourceException;

    /** Documents/attachments for an opportunity. Returns empty when none/unavailable. */
    List<SolicitationDocument> getOpportunityDocuments(String sourceId) throws SourceException;

    /** Raised for source-side failures (auth, network, malformed response) after retries. */
    class SourceException extends Exception {
        public SourceException(String message) {
            super(message);
        }

        public SourceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
