package com.jarvis.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.integrations.mcp.HttpMcpTransport;
import com.jarvis.integrations.mcp.McpClient;
import com.jarvis.integrations.mcp.McpToolInfo;
import com.jarvis.integrations.mcp.McpTransport;
import com.jarvis.memory.MemoryEntry;
import com.jarvis.memory.MemoryStore;
import com.jarvis.tools.RiskTier;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolRegistry;
import com.jarvis.tools.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Manages the user's Model Context Protocol server connections. Each server (name → URL + optional
 * bearer token) persists in the {@link MemoryStore} (scope {@code mcp_servers}) — the same local
 * store that holds provider API keys — so connections survive restarts. The token is kept locally
 * and <b>never</b> returned by {@link #list()} or logged. Connecting performs the MCP handshake and
 * discovers the server's tools; every add / remove / connect / tool call is audited.
 *
 * <p><b>Tool bridge.</b> When constructed with a {@link ToolRegistry}, each discovered MCP tool is
 * registered into it as {@code mcp_<server>_<tool>}, which makes it visible to the chat brain (the
 * policy re-reads the registry on every turn, so new tools appear with no restart). Removing a
 * server deregisters its tools. Every bridged call routes through {@link #call} and is audited.
 *
 * <p>Dormant by default — no server is contacted until one is added, listed, or refreshed. The
 * transport is injected so the logic is unit-tested against a fake MCP server offline.
 */
final class McpService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCOPE = "mcp_servers";

    /** A server configuration (URL + optional token). */
    record Cfg(String name, String url, String token) {
    }

    /** Public view of a server — never carries the token, only whether one is set. */
    record ServerView(String name, String url, boolean hasToken, boolean connected, String error,
            List<String> tools) {
    }

    private record Status(boolean connected, String error, List<String> tools) {
    }

    private final AuditLog audit;              // nullable
    private final Function<Cfg, McpTransport> transportFactory;
    private final ToolRegistry toolRegistry;   // nullable → no tool bridging
    private final MemoryStore<String> store;   // nullable → no persistence
    private final Map<String, Cfg> servers = new LinkedHashMap<>();
    private final Map<String, Status> statusCache = new LinkedHashMap<>();
    private final Map<String, List<String>> bridgedTools = new LinkedHashMap<>();

    McpService(AuditLog audit) {
        this(audit, cfg -> new HttpMcpTransport(cfg.url(), cfg.token()), null, null);
    }

    McpService(AuditLog audit, Function<Cfg, McpTransport> transportFactory) {
        this(audit, transportFactory, null, null);
    }

    McpService(AuditLog audit, ToolRegistry toolRegistry, MemoryStore<String> store) {
        this(audit, cfg -> new HttpMcpTransport(cfg.url(), cfg.token()), toolRegistry, store);
    }

    McpService(AuditLog audit, Function<Cfg, McpTransport> transportFactory,
            ToolRegistry toolRegistry, MemoryStore<String> store) {
        this.audit = audit;
        this.transportFactory = transportFactory;
        this.toolRegistry = toolRegistry;
        this.store = store;
        loadPersisted();
    }

    /** Adds (or updates) a server and connects to it, returning its resulting status. */
    synchronized ServerView add(String name, String url, String token) {
        String key = name == null ? "" : name.strip();
        if (key.isEmpty() || url == null || url.isBlank()) {
            return new ServerView(key, url, false, false, "name and URL are required", List.of());
        }
        // Blank token on update keeps the existing one.
        Cfg prev = servers.get(key);
        String tok = token == null || token.isBlank() ? (prev == null ? "" : prev.token()) : token;
        Cfg cfg = new Cfg(key, url.strip(), tok);
        servers.put(key, cfg);
        persist(cfg);
        Status st = connect(cfg);
        statusCache.put(key, st);
        syncBridgedTools(key, st);
        record("mcp_add", key + " (" + url.strip() + ")",
                st.connected() ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE);
        return view(key, cfg.url(), tok != null && !tok.isBlank(), st);
    }

    /** Every configured server with its (cached, lazily connected) status. */
    synchronized List<ServerView> list() {
        List<ServerView> out = new ArrayList<>();
        for (Cfg cfg : servers.values()) {
            Status st = statusCache.get(cfg.name());
            if (st == null) {
                st = connect(cfg);
                statusCache.put(cfg.name(), st);
                syncBridgedTools(cfg.name(), st);
            }
            out.add(view(cfg.name(), cfg.url(), cfg.token() != null && !cfg.token().isBlank(), st));
        }
        return out;
    }

    /** Drops all cached statuses and reconnects on the next {@link #list()}. */
    synchronized void refresh() {
        statusCache.clear();
    }

    synchronized boolean remove(String name) {
        boolean had = servers.remove(name) != null;
        statusCache.remove(name);
        syncBridgedTools(name, new Status(false, "", List.of()));
        if (store != null) {
            store.delete(SCOPE, name);
        }
        if (had) {
            record("mcp_remove", name, AuditOutcome.SUCCESS);
        }
        return had;
    }

    /** Invokes a tool on a named server (audited). Returns the tool's text output or an error string. */
    synchronized String call(String server, String tool, JsonNode arguments) {
        Cfg cfg = servers.get(server);
        if (cfg == null) {
            return "(error: no MCP server named '" + server + "')";
        }
        McpClient client = new McpClient(transportFactory.apply(cfg));
        if (!client.available()) {
            return "(error: MCP server '" + server + "' is not configured)";
        }
        client.initialize();
        String out = client.callTool(tool, arguments);
        record("mcp_tool_call", server + "/" + tool,
                out.startsWith("(error") ? AuditOutcome.FAILURE : AuditOutcome.SUCCESS);
        return out;
    }

    synchronized int count() {
        return servers.size();
    }

    // ---- internals ----

    private Status connect(Cfg cfg) {
        McpClient client = new McpClient(transportFactory.apply(cfg));
        if (!client.available()) {
            return new Status(false, "not configured", List.of());
        }
        try {
            boolean ok = client.initialize();
            if (!ok) {
                return new Status(false, "handshake failed", List.of());
            }
            List<String> tools = new ArrayList<>();
            for (McpToolInfo t : client.listTools()) {
                tools.add(t.name());
            }
            return new Status(true, "", tools);
        } catch (RuntimeException e) {
            return new Status(false, "unreachable", List.of());
        }
    }

    /**
     * Reconciles the bridged registry entries for {@code server} with its current tool list:
     * deregisters what was previously bridged, then registers one adapter per discovered tool
     * under {@code mcp_<server>_<tool>}. No-op without a registry.
     */
    private void syncBridgedTools(String server, Status st) {
        if (toolRegistry == null) {
            return;
        }
        List<String> old = bridgedTools.remove(server);
        if (old != null) {
            for (String name : old) {
                toolRegistry.deregister(name);
            }
        }
        if (!st.connected() || st.tools().isEmpty()) {
            return;
        }
        List<String> registered = new ArrayList<>();
        for (String toolName : st.tools()) {
            String bridged = bridgedName(server, toolName);
            if (toolRegistry.lookup(bridged).isPresent()) {
                continue;   // never clobber an existing tool (first-wins, like the registry)
            }
            toolRegistry.register(new McpBridgedTool(bridged, server, toolName));
            registered.add(bridged);
        }
        bridgedTools.put(server, registered);
    }

    /** The registry name an MCP tool is bridged under (sanitized for the TOOL: protocol). */
    static String bridgedName(String server, String tool) {
        return ("mcp_" + server + "_" + tool).replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    /** Adapter making one remote MCP tool look like a native {@link Tool} to the brain. */
    private final class McpBridgedTool implements Tool {
        private final String name;
        private final String server;
        private final String remoteTool;

        McpBridgedTool(String name, String server, String remoteTool) {
            this.name = name;
            this.server = server;
            this.remoteTool = remoteTool;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "MCP tool '" + remoteTool + "' on connected server '" + server
                    + "'. Pass its arguments as JSON.";
        }

        @Override
        public ToolResult execute(ToolCall toolCall) {
            JsonNode args = MAPPER.valueToTree(
                    toolCall.arguments() == null ? Map.of() : toolCall.arguments());
            String out = call(server, remoteTool, args);
            return out.startsWith("(error") ? ToolResult.error(out) : ToolResult.ok(out);
        }
    }

    // ---- persistence ----

    private void loadPersisted() {
        if (store == null) {
            return;
        }
        for (MemoryEntry<String> e : store.query(SCOPE)) {
            try {
                JsonNode c = MAPPER.readTree(e.value());
                servers.put(e.key(), new Cfg(e.key(), c.path("url").asText(""),
                        c.path("token").asText("")));
            } catch (Exception ex) {
                // An unreadable row is skipped, never fatal.
            }
        }
    }

    private void persist(Cfg cfg) {
        if (store == null) {
            return;
        }
        ObjectNode o = MAPPER.createObjectNode();
        o.put("url", cfg.url());
        o.put("token", cfg.token() == null ? "" : cfg.token());
        store.put(SCOPE, cfg.name(), o.toString());
    }

    private static ServerView view(String name, String url, boolean hasToken, Status st) {
        return new ServerView(name, url, hasToken, st.connected(), st.error(), st.tools());
    }

    private void record(String action, String detail, AuditOutcome outcome) {
        if (audit == null) {
            return;
        }
        audit.record(new AuditEvent(AuditCategory.TOOL_INVOCATION, action, AuditTrigger.USER,
                RiskTier.READ_ONLY, outcome, detail));
    }
}
