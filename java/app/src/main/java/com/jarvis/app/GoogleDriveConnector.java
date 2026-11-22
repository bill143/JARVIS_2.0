package com.jarvis.app;

import com.jarvis.solicitations.DocumentConnector;
import com.jarvis.solicitations.DocumentRef;
import java.util.List;

/**
 * Read-only Google Drive connector, folder-scoped. The Drive REST calls live behind a
 * {@link DriveTransport} seam so the connector can be unit-tested offline and, crucially, so it is
 * <b>dormant by default</b>: a stock deployment ships without a Drive transport, {@link #available()}
 * is {@code false}, and searches return empty with a clear health message. The connector exposes no
 * write/move/delete operation — read-only is structural. Access is limited to the configured
 * {@code GOOGLE_DRIVE_ALLOWED_FOLDER_IDS}.
 */
final class GoogleDriveConnector implements DocumentConnector {

    /** Seam to the Drive files API (list/get within the allowed folder scope). */
    interface DriveTransport {
        boolean available();

        List<DocumentRef> search(String query, int max, List<String> folderScope) throws Exception;

        DocumentRef getById(String id, List<String> folderScope) throws Exception;
    }

    private final DriveTransport transport;   // null → dormant
    private final List<String> allowedFolders;
    private final boolean enabled;

    GoogleDriveConnector(boolean enabled, List<String> allowedFolders, DriveTransport transport) {
        this.enabled = enabled;
        this.allowedFolders = allowedFolders == null ? List.of() : List.copyOf(allowedFolders);
        this.transport = transport;
    }

    /** Reads {@code GOOGLE_DRIVE_ENABLED} + {@code GOOGLE_DRIVE_ALLOWED_FOLDER_IDS}; no transport → dormant. */
    static GoogleDriveConnector fromEnvironment(DriveTransport transport) {
        boolean enabled = "true".equalsIgnoreCase(
                System.getenv().getOrDefault("GOOGLE_DRIVE_ENABLED", "false").strip());
        List<String> folders = splitCsv(System.getenv("GOOGLE_DRIVE_ALLOWED_FOLDER_IDS"));
        return new GoogleDriveConnector(enabled, folders, transport);
    }

    @Override
    public String name() {
        return "google-drive";
    }

    @Override
    public boolean available() {
        return enabled && transport != null && transport.available();
    }

    @Override
    public String health() {
        if (!enabled) {
            return "not configured — set GOOGLE_DRIVE_ENABLED=true";
        }
        if (transport == null || !transport.available()) {
            return "enabled but not authorized — connect Google Drive (read-only) and set folder scope";
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
            throw new ConnectorException("Google Drive search failed (auth or permission?)", e);
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
            throw new ConnectorException("Google Drive lookup failed (auth or permission?)", e);
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
