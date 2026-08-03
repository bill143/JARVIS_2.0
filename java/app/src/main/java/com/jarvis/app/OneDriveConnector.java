package com.jarvis.app;

import com.jarvis.solicitations.DocumentConnector;
import com.jarvis.solicitations.DocumentRef;
import java.util.List;

/**
 * Read-only OneDrive connector, folder-scoped. Same shape and guarantees as
 * {@link GoogleDriveConnector}: dormant by default (no transport → not configured), no write/delete
 * surface, access limited to {@code ONEDRIVE_ALLOWED_FOLDER_IDS}. The Microsoft Graph calls live
 * behind a {@link GraphTransport} seam so the connector is offline-testable and ships inert.
 */
final class OneDriveConnector implements DocumentConnector {

    /** Seam to the Microsoft Graph drive API (list/get within the allowed folder scope). */
    interface GraphTransport {
        boolean available();

        List<DocumentRef> search(String query, int max, List<String> folderScope) throws Exception;

        DocumentRef getById(String id, List<String> folderScope) throws Exception;
    }

    private final GraphTransport transport;   // null → dormant
    private final List<String> allowedFolders;
    private final boolean enabled;

    OneDriveConnector(boolean enabled, List<String> allowedFolders, GraphTransport transport) {
        this.enabled = enabled;
        this.allowedFolders = allowedFolders == null ? List.of() : List.copyOf(allowedFolders);
        this.transport = transport;
    }

    /** Reads {@code ONEDRIVE_ENABLED} + {@code ONEDRIVE_ALLOWED_FOLDER_IDS}; no transport → dormant. */
    static OneDriveConnector fromEnvironment(GraphTransport transport) {
        boolean enabled = "true".equalsIgnoreCase(
                System.getenv().getOrDefault("ONEDRIVE_ENABLED", "false").strip());
        List<String> folders = splitCsv(System.getenv("ONEDRIVE_ALLOWED_FOLDER_IDS"));
        return new OneDriveConnector(enabled, folders, transport);
    }

    @Override
    public String name() {
        return "onedrive";
    }

    @Override
    public boolean available() {
        return enabled && transport != null && transport.available();
    }

    @Override
    public String health() {
        if (!enabled) {
            return "not configured — set ONEDRIVE_ENABLED=true";
        }
        if (transport == null || !transport.available()) {
            return "enabled but not authorized — connect OneDrive (read-only) and set folder scope";
        }
        return allowedFolders.isEmpty() ? "connected (whole drive — set folder scope to restrict)"
                : "connected (" + allowedFolders.size() + " folder scope)";
    }

    @Override
    public List<DocumentRef> search(String query, int max) throws ConnectorException {
        if (!available()) {
            return List.of();
        }
        try {
            return transport.search(query, max, allowedFolders);
        } catch (Exception e) {
            throw new ConnectorException("OneDrive search failed (auth or permission?)", e);
        }
    }

    @Override
    public DocumentRef getById(String id) throws ConnectorException {
        if (!available()) {
            return null;
        }
        try {
            return transport.getById(id, allowedFolders);
        } catch (Exception e) {
            throw new ConnectorException("OneDrive lookup failed (auth or permission?)", e);
        }
    }

    private static List<String> splitCsv(String v) {
        if (v == null || v.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(v.split(",")).map(String::strip)
                .filter(s -> !s.isBlank()).toList();
    }
}
