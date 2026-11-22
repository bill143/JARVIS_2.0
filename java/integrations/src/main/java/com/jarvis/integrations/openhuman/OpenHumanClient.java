package com.jarvis.integrations.openhuman;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * A thin, typed view over the OpenHuman core's local HTTP API, built on the
 * {@link OpenHumanTransport} seam. Read-only by design: OpenHuman is JARVIS's <em>advisor</em>, so
 * these calls consult its memory and reasoning but never ask it to act.
 *
 * <p>Confirmed endpoints: {@code GET /health}, {@code GET /schema}, and JSON-RPC 2.0 at
 * {@code POST /rpc}. The exact RPC <em>method names</em> for memory search / consult are not yet
 * published by OpenHuman (they are discoverable at runtime via {@link #schema()}), so they are
 * configurable here with documented defaults and can be overridden without a code change.
 */
public final class OpenHumanClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Default JSON-RPC method names — override once confirmed against a live core via {@code /schema}. */
    public static final String DEFAULT_MEMORY_SEARCH_METHOD = "memory.search";
    public static final String DEFAULT_CONSULT_METHOD = "agent.chat";
    public static final String DEFAULT_MEMORY_WRITE_METHOD = "memory.add";

    private final OpenHumanTransport transport;
    private final String memorySearchMethod;
    private final String consultMethod;
    private final String memoryWriteMethod;
    private int rpcId;

    public OpenHumanClient(OpenHumanTransport transport) {
        this(transport, DEFAULT_MEMORY_SEARCH_METHOD, DEFAULT_CONSULT_METHOD);
    }

    public OpenHumanClient(OpenHumanTransport transport, String memorySearchMethod, String consultMethod) {
        this(transport, memorySearchMethod, consultMethod, DEFAULT_MEMORY_WRITE_METHOD);
    }

    public OpenHumanClient(OpenHumanTransport transport, String memorySearchMethod,
            String consultMethod, String memoryWriteMethod) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.memorySearchMethod = orDefault(memorySearchMethod, DEFAULT_MEMORY_SEARCH_METHOD);
        this.consultMethod = orDefault(consultMethod, DEFAULT_CONSULT_METHOD);
        this.memoryWriteMethod = orDefault(memoryWriteMethod, DEFAULT_MEMORY_WRITE_METHOD);
    }

    /** Whether the core is configured and reachable (transport available). */
    public boolean available() {
        return transport.available();
    }

    /** Health probe: {@code GET /health} returns 2xx when the core is up. */
    public boolean healthy() {
        try {
            return transport.available() && transport.send("GET", "/health", null).ok();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /** The core's self-described capability schema ({@code GET /schema}) — used to discover methods. */
    public String schema() throws IOException, InterruptedException {
        return require(transport.send("GET", "/schema", null), "OpenHuman schema").body();
    }

    /** Searches OpenHuman's Memory Tree by meaning; returns a readable summary. */
    public String memorySearch(String query, int limit) throws IOException, InterruptedException {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("query", query == null ? "" : query);
        params.put("limit", Math.min(Math.max(1, limit), 50));
        return renderResult(rpc(memorySearchMethod, params));
    }

    /**
     * Writes a durable memory into the shared brain. This is a MUTATING call — callers must gate it
     * (risk-tier permission gate + {@link MemoryWritePolicy}); this method performs the write
     * unconditionally once reached, so it must not be reached for an unauthorized role.
     */
    public String memoryWrite(String content, String tags) throws IOException, InterruptedException {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("content", content == null ? "" : content);
        if (tags != null && !tags.isBlank()) {
            params.put("tags", tags);
        }
        return renderResult(rpc(memoryWriteMethod, params));
    }

    /**
     * Private-mode concerns advertised by the core's {@code /schema} (RFC 0001 §6). An empty list
     * means the schema is clean of public-network / payment / marketplace markers.
     */
    public List<String> schemaConcerns() throws IOException, InterruptedException {
        return PrivateModeAssertion.scan(schema());
    }

    /** Consults OpenHuman as an advisor on {@code question} within {@code context}; returns its reply. */
    public String consult(String question, String context) throws IOException, InterruptedException {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("message", question == null ? "" : question);
        if (context != null && !context.isBlank()) {
            params.put("context", context);
        }
        return renderResult(rpc(consultMethod, params));
    }

    // ---- JSON-RPC plumbing ------------------------------------------------------------------

    /** Sends a JSON-RPC 2.0 request to {@code /rpc} and returns its {@code result} node. */
    JsonNode rpc(String method, ObjectNode params) throws IOException, InterruptedException {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", ++rpcId);
        envelope.put("method", method);
        envelope.set("params", params);
        OpenHumanResponse response = require(
                transport.send("POST", "/rpc", envelope.toString()), "OpenHuman " + method);
        JsonNode root = MAPPER.readTree(response.body());
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            throw new IOException("OpenHuman " + method + " error: "
                    + error.path("message").asText(error.toString()));
        }
        return root.path("result");
    }

    private static String renderResult(JsonNode result) {
        if (result == null || result.isNull() || result.isMissingNode()) {
            return "(no result)";
        }
        if (result.isTextual()) {
            return result.asText();
        }
        // Common shapes: {text:...} / {content:...} / {answer:...}
        for (String field : new String[] {"text", "content", "answer", "message", "summary"}) {
            if (result.hasNonNull(field) && result.get(field).isTextual()) {
                return result.get(field).asText();
            }
        }
        return result.toPrettyString();
    }

    private static OpenHumanResponse require(OpenHumanResponse r, String action) throws IOException {
        if (r.ok()) {
            return r;
        }
        throw new IOException(action + " failed (HTTP " + r.status() + ")");
    }

    private static String orDefault(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
