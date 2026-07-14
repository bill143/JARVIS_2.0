package com.jarvis.solicitations;

import java.util.List;

/**
 * Read-only connector to an external document system (Google Drive, OneDrive, …). The interface
 * exposes <b>no write, move, or delete operation</b> — read-only is enforced structurally, not by a
 * runtime flag, so a connector physically cannot mutate the operator's files. Access is folder-scoped
 * via configuration; connectors are dormant until credentials are present.
 */
public interface DocumentConnector {

    /** Connector name for badges/attribution (e.g. {@code google-drive}, {@code onedrive}). */
    String name();

    /** Whether credentials + folder scope are configured for live calls. */
    boolean available();

    /** A short health string for the UI ({@code "connected"}, {@code "not configured"}, an error). */
    String health();

    /** Searches accessible (scoped) files by name/content. Returns empty when unavailable. */
    List<DocumentRef> search(String query, int max) throws ConnectorException;

    /** Metadata for one file by id, or {@code null} if not found/permitted. */
    DocumentRef getById(String id) throws ConnectorException;

    /** Raised for connector-side failures (auth, permission, network). Carries a safe message. */
    class ConnectorException extends Exception {
        public ConnectorException(String message) {
            super(message);
        }

        public ConnectorException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
