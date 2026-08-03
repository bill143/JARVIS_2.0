package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.integrations.mcp.McpTransport;
import com.jarvis.memory.InMemoryStore;
import com.jarvis.memory.MemoryStore;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolRegistry;
import com.jarvis.tools.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpServiceTest {

    /** A fake MCP server: handshake, one tool ("echo"), and a canned tool result ("pong"). */
    private static McpTransport fake() {
        return jsonRpcBody -> {
            if (jsonRpcBody.contains("\"tools/list\"")) {
                return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":"
                        + "[{\"name\":\"echo\",\"description\":\"Echoes input\"}]}}";
            }
            if (jsonRpcBody.contains("\"tools/call\"")) {
                return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":"
                        + "[{\"type\":\"text\",\"text\":\"pong\"}]}}";
            }
            return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":"
                    + "{\"protocolVersion\":\"x\",\"capabilities\":{}}}";
        };
    }

    // ---- tool bridge (regression: connected MCP tools never reached the chat brain) ----

    @Test
    void connectingAServerBridgesItsToolsIntoTheRegistry() {
        ToolRegistry registry = new ToolRegistry();
        McpService svc = new McpService(null, cfg -> fake(), registry, null);
        svc.add("deepwiki", "http://example/mcp", "");

        Tool bridged = registry.lookup("mcp_deepwiki_echo").orElseThrow();
        assertTrue(bridged.description().contains("deepwiki"));
        // Invoking the bridged tool routes through the MCP server and returns its output.
        ToolResult r = bridged.execute(new ToolCall("mcp_deepwiki_echo", Map.of("q", "hi")));
        assertTrue(r.success());
        assertEquals("pong", r.output());
    }

    @Test
    void removingAServerDeregistersItsBridgedTools() {
        ToolRegistry registry = new ToolRegistry();
        McpService svc = new McpService(null, cfg -> fake(), registry, null);
        svc.add("deepwiki", "http://example/mcp", "");
        assertTrue(registry.lookup("mcp_deepwiki_echo").isPresent());

        assertTrue(svc.remove("deepwiki"));
        assertTrue(registry.lookup("mcp_deepwiki_echo").isEmpty());
        // The bridged call now fails cleanly if anything held a stale reference.
        assertEquals("(error: no MCP server named 'deepwiki')",
                svc.call("deepwiki", "echo", null));
    }

    @Test
    void bridgeNeverClobbersAnExistingTool() {
        ToolRegistry registry = new ToolRegistry();
        Tool existing = new Tool() {
            @Override
            public String name() {
                return "mcp_deepwiki_echo";
            }

            @Override
            public String description() {
                return "native";
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.ok("native");
            }
        };
        registry.register(existing);
        McpService svc = new McpService(null, cfg -> fake(), registry, null);
        svc.add("deepwiki", "http://example/mcp", "");
        // First-wins: the pre-existing tool stays; removing the server must not evict it either.
        assertEquals("native", registry.lookup("mcp_deepwiki_echo").orElseThrow().description());
        svc.remove("deepwiki");
        assertTrue(registry.lookup("mcp_deepwiki_echo").isPresent());
    }

    @Test
    void bridgedNamesAreSanitizedForTheToolProtocol() {
        assertEquals("mcp_my_docs_read_file", McpService.bridgedName("my docs", "read/file"));
    }

    @Test
    void worksWithoutARegistryOrStore() {
        McpService svc = new McpService(null, cfg -> fake());
        McpService.ServerView v = svc.add("deepwiki", "http://example/mcp", "");
        assertTrue(v.connected());
        assertEquals(java.util.List.of("echo"), v.tools());
    }

    // ---- persistence (regression: connections vanished on every restart) ----

    @Test
    void serversSurviveARestartViaTheStore() {
        MemoryStore<String> store = new InMemoryStore<>();
        McpService first = new McpService(null, cfg -> fake(), null, store);
        first.add("deepwiki", "http://example/mcp", "tok-123");

        // A brand-new service over the same store (= app restart) still knows the server
        // and reconnects lazily on list().
        ToolRegistry registry = new ToolRegistry();
        McpService second = new McpService(null, cfg -> fake(), registry, store);
        assertEquals(1, second.count());
        McpService.ServerView v = second.list().get(0);
        assertEquals("deepwiki", v.name());
        assertTrue(v.connected());
        assertTrue(v.hasToken());   // token persisted locally too
        // And the lazy reconnect re-bridges the tools.
        assertTrue(registry.lookup("mcp_deepwiki_echo").isPresent());
    }

    @Test
    void removePurgesThePersistedEntry() {
        MemoryStore<String> store = new InMemoryStore<>();
        McpService first = new McpService(null, cfg -> fake(), null, store);
        first.add("deepwiki", "http://example/mcp", "");
        first.remove("deepwiki");

        McpService second = new McpService(null, cfg -> fake(), null, store);
        assertEquals(0, second.count());
        assertTrue(second.list().isEmpty());
    }

    @Test
    void blankTokenOnUpdateKeepsTheExistingToken() {
        MemoryStore<String> store = new InMemoryStore<>();
        McpService svc = new McpService(null, cfg -> fake(), null, store);
        svc.add("gh", "http://example/mcp", "secret-1");
        McpService.ServerView updated = svc.add("gh", "http://example2/mcp", "");
        assertTrue(updated.hasToken());   // kept
        assertEquals("http://example2/mcp", updated.url());
        // Survives a restart with the kept token.
        McpService reloaded = new McpService(null, cfg -> fake(), null, store);
        assertTrue(reloaded.list().get(0).hasToken());
        assertFalse(reloaded.list().get(0).url().isEmpty());
    }
}
