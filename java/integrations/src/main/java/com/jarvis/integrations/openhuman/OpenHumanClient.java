package com.jarvis.integrations.openhuman;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Objects;

/**
 * A thin, typed view over the OpenHuman core's local HTTP API, built on the
 * {@link OpenHumanTransport} seam. Tier 1 (search/consult) is read-only by design: OpenHuman is
 * JARVIS's <em>advisor</em>, so those calls consult its memory and reasoning but never ask it to act.
 * The Tier-2 {@link #memoryWrite} capability crosses that line, so it is gated deny-by-default by a
 * {@link WritePolicy} — see that method's Javadoc for why.
 *
 * <p>Confirmed against the OpenHuman core source ({@code tinyhumansai/openhuman}): {@code GET
 * /health}, {@code GET /schema}, and JSON-RPC 2.0 at {@code POST /rpc}, bearer-authenticated. The RPC
 * <em>method names</em> below are the real, namespaced methods ({@code openhuman.<namespace>_<fn>}) —
 * not a guess — but the upstream project does not promise their stability (it carries a legacy-alias
 * table for past renames), so every literal method name is centralized in the constants below and is
 * override-able per instance without a code change.
 */
public final class OpenHumanClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Default JSON-RPC method names — the single place these literals live. */
    public static final String DEFAULT_MEMORY_SEARCH_METHOD = "openhuman.memory_query_namespace";
    public static final String DEFAULT_CONSULT_METHOD = "openhuman.agent_chat";
    public static final String DEFAULT_MEMORY_WRITE_METHOD = "openhuman.memory_doc_put";

    /** The Memory Tree namespace used when a caller doesn't specify one. */
    public static final String DEFAULT_NAMESPACE = "default";

    /**
     * Governs {@link #memoryWrite}. <b>Deny-by-default</b>: OpenHuman's own {@code /rpc}
     * {@code memory.doc_put} path does not re-run its own write-approval policy (verified against
     * source — that check only exists on its separate MCP-tool path), so a bearer token alone is
     * sufficient to write there. This policy is JARVIS's only gate on that call, which is why the
     * default implementation refuses everything.
     */
    @FunctionalInterface
    public interface WritePolicy {

        /** Whether a write to {@code namespace}/{@code key} is currently permitted. */
        boolean permits(String namespace, String key);

        /** Refuses every write. The posture unless a caller explicitly supplies a permissive policy. */
        WritePolicy DENY_ALL = (namespace, key) -> false;
    }

    private final OpenHumanTransport transport;
    private final String memorySearchMethod;
    private final String consultMethod;
    private final String memoryWriteMethod;
    private final WritePolicy writePolicy;
    private int rpcId;

    public OpenHumanClient(OpenHumanTransport transport) {
        this(transport, DEFAULT_MEMORY_SEARCH_METHOD, DEFAULT_CONSULT_METHOD);
    }

    public OpenHumanClient(OpenHumanTransport transport, String memorySearchMethod, String consultMethod) {
        this(transport, memorySearchMethod, consultMethod, DEFAULT_MEMORY_WRITE_METHOD, WritePolicy.DENY_ALL);
    }

    public OpenHumanClient(OpenHumanTransport transport, String memorySearchMethod, String consultMethod,
            String memoryWriteMethod, WritePolicy writePolicy) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.memorySearchMethod = orDefault(memorySearchMethod, DEFAULT_MEMORY_SEARCH_METHOD);
        this.consultMethod = orDefault(consultMethod, DEFAULT_CONSULT_METHOD);
        this.memoryWriteMethod = orDefault(memoryWriteMethod, DEFAULT_MEMORY_WRITE_METHOD);
        this.writePolicy = writePolicy == null ? WritePolicy.DENY_ALL : writePolicy;
    }

    /** Whether the core is configured and reachable (transport available). */
    public boolean available() {
        return transport.available();
    }

    /** Health probe: {@code GET /health}. Prefer {@link #health()} for degraded-vs-down detail. */
    public boolean healthy() {
        try {
            return health().reachable();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * Health probe with detail: distinguishes "reachable but degraded" from "critically down" per
     * the core's documented {@code CRITICAL_COMPONENTS} behavior (503 only for those; other
     * components failing still returns 200 with {@code degraded: true}).
     */
    public OpenHumanHealthStatus health() throws IOException, InterruptedException {
        if (!transport.available()) {
            return OpenHumanHealthStatus.NOT_CONFIGURED;
        }
        OpenHumanResponse r = transport.send("GET", "/health", null);
        boolean degraded = false;
        try {
            degraded = MAPPER.readTree(r.body()).path("degraded").asBoolean(false);
        } catch (Exception ignored) {
            // Non-JSON or empty body — fall back to the status code alone.
        }
        return new OpenHumanHealthStatus(r.ok(), degraded, r.status());
    }

    /** The core's self-described capability schema ({@code GET /schema}) — used to discover methods. */
    public String schema() throws IOException, InterruptedException {
        return require(transport.send("GET", "/schema", null), "OpenHuman schema").body();
    }

    /** Searches OpenHuman's Memory Tree by meaning within {@link #DEFAULT_NAMESPACE}. */
    public String memorySearch(String query, int limit) throws IOException, InterruptedException {
        return memorySearch(DEFAULT_NAMESPACE, query, limit);
    }

    /** Searches OpenHuman's Memory Tree by meaning within {@code namespace}; returns a readable summary. */
    public String memorySearch(String namespace, String query, int limit)
            throws IOException, InterruptedException {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("namespace", namespace == null || namespace.isBlank() ? DEFAULT_NAMESPACE : namespace);
        params.put("query", query == null ? "" : query);
        params.put("limit", Math.min(Math.max(1, limit), 50));
        return renderResult(rpc(memorySearchMethod, params));
    }

    /** Consults OpenHuman as an advisor on {@code question} within {@code context}; returns its reply. */
    public String consult(String question, String context) throws IOException, InterruptedException {
        ObjectNode params = MAPPER.createObjectNode();
        String message = question == null ? "" : question;
        if (context != null && !context.isBlank()) {
            // openhuman.agent_chat has no separate "context" field — fold it into the message.
            message = "Context: " + context + "\n\nQuestion: " + message;
        }
        params.put("message", message);
        return renderResult(rpc(consultMethod, params));
    }

    /**
     * Writes (upserts, keyed by {@code key}) a document to OpenHuman's memory. <b>Deny-by-default</b>:
     * refused locally — before any network call — unless this client's {@link WritePolicy} explicitly
     * permits {@code namespace}/{@code key}. See {@link WritePolicy} for why JARVIS enforces this
     * itself rather than relying on OpenHuman.
     *
     * @return the OpenHuman-assigned {@code document_id}
     * @throws SecurityException if the write policy denies the call
     */
    public String memoryWrite(String namespace, String key, String title, String content)
            throws IOException, InterruptedException {
        String ns = namespace == null || namespace.isBlank() ? DEFAULT_NAMESPACE : namespace;
        if (!writePolicy.permits(ns, key)) {
            throw new SecurityException(
                    "OpenHuman memory write denied by policy: namespace=" + ns + " key=" + key);
        }
        ObjectNode params = MAPPER.createObjectNode();
        params.put("namespace", ns);
        params.put("key", key == null ? "" : key);
        params.put("title", title == null ? "" : title);
        params.put("content", content == null ? "" : content);
        return rpc(memoryWriteMethod, params).path("document_id").asText("");
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
        // Common shapes: {text:...} / {content:...} / {answer:...} / {result:...} (agent_chat)
        for (String field : new String[] {"result", "text", "content", "answer", "message", "summary"}) {
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
