package com.jarvis.integrations.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Production {@link McpTransport}: POSTs JSON-RPC to an MCP server over the JDK {@link HttpClient}
 * (no third-party SDK). Accepts both a plain JSON response and an {@code text/event-stream} response
 * (from which it extracts the last {@code data:} JSON frame). An optional bearer token authenticates
 * the connection and is never logged.
 *
 * <p><b>Dormant without a URL:</b> {@link #available()} is {@code false} and no request is made.
 */
public final class HttpMcpTransport implements McpTransport {

    private final String url;     // nullable → dormant
    private final String token;   // nullable; never logged
    private final HttpClient client;

    public HttpMcpTransport(String url, String token) {
        this.url = url == null || url.isBlank() ? null : url.strip();
        this.token = token == null || token.isBlank() ? null : token;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public boolean available() {
        return url != null;
    }

    @Override
    public String post(String jsonRpcBody) throws IOException, InterruptedException {
        if (!available()) {
            throw new IOException("MCP server is not configured (no URL).");
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                // MCP Streamable HTTP: server may reply with JSON or an SSE stream.
                .header("Accept", "application/json, text/event-stream")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(jsonRpcBody));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        String body = res.body() == null ? "" : res.body();
        if (res.statusCode() / 100 != 2) {
            throw new IOException("MCP server returned HTTP " + res.statusCode());
        }
        return extractJson(body);
    }

    /** Returns the JSON object from a plain body, or the last {@code data:} frame of an SSE stream. */
    static String extractJson(String body) {
        String trimmed = body.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        // SSE: take the last non-empty "data:" payload.
        String last = "";
        for (String line : trimmed.split("\n")) {
            String l = line.strip();
            if (l.startsWith("data:")) {
                String payload = l.substring(5).strip();
                if (!payload.isEmpty() && !payload.equals("[DONE]")) {
                    last = payload;
                }
            }
        }
        return last.isEmpty() ? trimmed : last;
    }
}
