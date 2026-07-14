package com.jarvis.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.integrations.mcp.HttpMcpTransport;
import com.jarvis.integrations.mcp.McpClient;
import com.jarvis.integrations.mcp.McpToolInfo;
import com.jarvis.integrations.mcp.McpTransport;
import com.jarvis.tools.RiskTier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Manages the user's Model Context Protocol server connections. Each server (name → URL + optional
 * bearer token) is held in memory for the session; the token is kept locally and <b>never</b>
 * returned by {@link #list()} or logged (nor written to disk). Connecting performs the MCP handshake
 * and discovers the server's tools; every add / remove / connect / tool call is audited.
 *
 * <p>Dormant by default — no server is contacted until one is added or explicitly refreshed. The
 * transport is injected so the logic is unit-tested against a fake MCP server offline.
 */
final class McpService {

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
    private final Map<String, Cfg> servers = new LinkedHashMap<>();
    private final Map<String, Status> statusCache = new LinkedHashMap<>();

    McpService(AuditLog audit) {
        this(audit, cfg -> new HttpMcpTransport(cfg.url(), cfg.token()));
    }

    McpService(AuditLog audit, Function<Cfg, McpTransport> transportFactory) {
        this.audit = audit;
        this.transportFactory = transportFactory;
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
        Status st = connect(cfg);
        statusCache.put(key, st);
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
