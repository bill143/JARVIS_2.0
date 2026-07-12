package com.jarvis.integrations.openhuman;

import java.io.IOException;

/**
 * Transport seam for the OpenHuman core's local HTTP API (confirmed surface: {@code GET /health},
 * {@code GET /schema}, JSON-RPC at {@code POST /rpc}, all bearer-authenticated at
 * {@code http://127.0.0.1:<port>}). This is the single network boundary — {@link OpenHumanClient}
 * logic is unit-tested with a fake transport (no running core, no token), and the real
 * {@link HttpOpenHumanTransport} is swapped in for production.
 *
 * <p>OpenHuman is a separate GPL-3.0 process; JARVIS only ever speaks HTTP to it (arm's-length), so
 * no OpenHuman code is linked into JARVIS.
 */
@FunctionalInterface
public interface OpenHumanTransport {

    /**
     * Sends a request to the OpenHuman core.
     *
     * @param method HTTP method ({@code GET}, {@code POST})
     * @param path path beginning with {@code /}, relative to the core base URL
     * @param jsonBody request body JSON, or {@code null} for a body-less request
     */
    OpenHumanResponse send(String method, String path, String jsonBody)
            throws IOException, InterruptedException;

    /** Whether this transport is configured to make live calls (a base URL + token are present). */
    default boolean available() {
        return true;
    }
}
