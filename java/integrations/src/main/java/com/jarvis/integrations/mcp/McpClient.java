package com.jarvis.integrations.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal Model Context Protocol client over a {@link McpTransport}. Speaks JSON-RPC 2.0:
 * {@code initialize} (handshake), {@code tools/list} (discovery), and {@code tools/call}
 * (invocation). All logic here is transport-agnostic and unit-tested with a fake transport; the real
 * {@link HttpMcpTransport} is swapped in for production.
 *
 * <p>Never throws from {@link #listTools()} or {@link #callTool}: connection or protocol problems
 * come back as an empty list or an error string, so a misbehaving server can't crash JARVIS.
 */
public final class McpClient {

    /** MCP revision this client advertises in the handshake. */
    public static final String PROTOCOL_VERSION = "2025-06-18";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpTransport transport;
    private final AtomicInteger ids = new AtomicInteger(1);

    public McpClient(McpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public boolean available() {
        return transport.available();
    }

    /** Performs the MCP handshake. Returns {@code true} if the server responded without an error. */
    public boolean initialize() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", MAPPER.createObjectNode());
        ObjectNode info = params.putObject("clientInfo");
        info.put("name", "JARVIS");
        info.put("version", "0.1.0");
        JsonNode res = rpc("initialize", params);
        return res != null && res.has("result");
    }

    /** Lists the server's tools ({@code tools/list}). Empty on any error. */
    public List<McpToolInfo> listTools() {
        List<McpToolInfo> out = new ArrayList<>();
        JsonNode res = rpc("tools/list", MAPPER.createObjectNode());
        if (res == null) {
            return out;
        }
        JsonNode tools = res.path("result").path("tools");
        if (tools.isArray()) {
            for (JsonNode t : tools) {
                String name = t.path("name").asText("");
                if (!name.isBlank()) {
                    out.add(new McpToolInfo(name, t.path("description").asText("")));
                }
            }
        }
        return out;
    }

    /**
     * Calls a tool ({@code tools/call}) and returns the concatenated text content of the result, or a
     * short {@code (error: …)} string. Never throws.
     *
     * @param arguments a JSON object of arguments, or {@code null} for none
     */
    public String callTool(String name, JsonNode arguments) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments != null && arguments.isObject()
                ? arguments : MAPPER.createObjectNode());
        JsonNode res = rpc("tools/call", params);
        if (res == null) {
            return "(error: no response from MCP server)";
        }
        if (res.has("error")) {
            return "(error: " + res.path("error").path("message").asText("MCP error") + ")";
        }
        StringBuilder sb = new StringBuilder();
        JsonNode content = res.path("result").path("content");
        if (content.isArray()) {
            for (JsonNode c : content) {
                if ("text".equals(c.path("type").asText())) {
                    sb.append(c.path("text").asText());
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : res.path("result").toString();
    }

    /** Sends one JSON-RPC request; returns the parsed response, or {@code null} on any failure. */
    private JsonNode rpc(String method, JsonNode params) {
        if (!transport.available()) {
            return null;
        }
        try {
            ObjectNode req = MAPPER.createObjectNode();
            req.put("jsonrpc", "2.0");
            req.put("id", ids.getAndIncrement());
            req.put("method", method);
            req.set("params", params);
            String body = transport.post(MAPPER.writeValueAsString(req));
            if (body == null || body.isBlank()) {
                return null;
            }
            return MAPPER.readTree(body);
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
