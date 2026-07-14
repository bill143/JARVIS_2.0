package com.jarvis.integrations.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A transport that answers by JSON-RPC method, echoing nothing it shouldn't. */
    private static McpTransport fake() {
        return body -> {
            if (body.contains("\"tools/list\"")) {
                return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":["
                        + "{\"name\":\"search\",\"description\":\"Search docs\"},"
                        + "{\"name\":\"fetch\"}]}}";
            }
            if (body.contains("\"tools/call\"")) {
                return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":["
                        + "{\"type\":\"text\",\"text\":\"hello \"},{\"type\":\"text\",\"text\":\"world\"}]}}";
            }
            return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{}}}";
        };
    }

    @Test
    void initializeSucceedsOnAResult() {
        assertTrue(new McpClient(fake()).initialize());
    }

    @Test
    void listToolsParsesNamesAndDescriptions() {
        List<McpToolInfo> tools = new McpClient(fake()).listTools();
        assertEquals(2, tools.size());
        assertEquals("search", tools.get(0).name());
        assertEquals("Search docs", tools.get(0).description());
        assertEquals("fetch", tools.get(1).name());
    }

    @Test
    void callToolConcatenatesTextContent() {
        assertEquals("hello world", new McpClient(fake()).callTool("search", null));
    }

    @Test
    void errorResponseBecomesErrorString() {
        McpTransport err = body -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"message\":\"nope\"}}";
        String out = new McpClient(err).callTool("x", null);
        assertTrue(out.contains("nope"));
        assertTrue(out.startsWith("(error"));
    }

    @Test
    void dormantTransportNeverConnects() {
        McpTransport dormant = new McpTransport() {
            @Override public String post(String body) {
                throw new AssertionError("must not be called when unavailable");
            }
            @Override public boolean available() {
                return false;
            }
        };
        McpClient c = new McpClient(dormant);
        assertFalse(c.initialize());
        assertTrue(c.listTools().isEmpty());
    }

    @Test
    void sseFramesAreUnwrapped() {
        String sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n\n";
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}",
                HttpMcpTransport.extractJson(sse));
    }

    @Test
    void plainJsonBodyPassesThrough() {
        assertEquals("{\"a\":1}", HttpMcpTransport.extractJson("  {\"a\":1}  "));
    }
}
