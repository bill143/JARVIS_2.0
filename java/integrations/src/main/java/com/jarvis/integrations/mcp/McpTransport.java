package com.jarvis.integrations.mcp;

import java.io.IOException;

/**
 * Transport seam for a Model Context Protocol server's HTTP endpoint (JSON-RPC 2.0 over
 * "Streamable HTTP" — a single {@code POST} that returns either a JSON body or an
 * {@code text/event-stream} whose {@code data:} line carries the JSON-RPC response).
 *
 * <p>This is the one network boundary, so {@link McpClient} is unit-tested against a fake transport
 * (no server, no token) and the real {@link HttpMcpTransport} is swapped in for production. JARVIS
 * only ever speaks HTTP to an MCP server — arm's-length, whitelist-clean (JDK HttpClient + Jackson).
 */
@FunctionalInterface
public interface McpTransport {

    /**
     * Sends one JSON-RPC request body and returns the raw response text (JSON object, or the JSON
     * extracted from an SSE {@code data:} frame).
     *
     * @param jsonRpcBody a complete JSON-RPC 2.0 request
     */
    String post(String jsonRpcBody) throws IOException, InterruptedException;

    /** Whether this transport is configured to make live calls (a URL is present). */
    default boolean available() {
        return true;
    }
}
