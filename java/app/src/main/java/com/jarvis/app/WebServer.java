package com.jarvis.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.api.ChatRequest;
import com.jarvis.api.ChatResponse;
import com.jarvis.api.JarvisApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP face for the dashboard, built solely on the JDK's {@link HttpServer} — the
 * dependency whitelist stays untouched.
 *
 * <p>Routes: {@code GET /} serves the embedded dashboard page, {@code GET /status} reports mode
 * and model, {@code POST /chat} takes {@code {"prompt": "..."}} and answers through
 * {@link JarvisApi} with {@code {"completed": bool, "response": "...", "toolSteps": n}}.
 */
public final class WebServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;

    /** Analyzes a browser-supplied image (webcam frame) against a question. */
    @FunctionalInterface
    public interface VisionHook {
        String analyze(byte[] png, String question) throws Exception;
    }

    private WebServer(HttpServer server) {
        this.server = server;
    }

    /** Starts the server on {@code port} (0 picks a free port) and returns it running. */
    public static WebServer start(JarvisApi api, boolean online, String model, int port)
            throws IOException {
        return start(api, online, model, port, null, null, false);
    }

    /** Backwards-compatible overload (no Google status). */
    public static WebServer start(JarvisApi api, boolean online, String model, int port,
            HardwareMonitor monitor, VisionHook vision) throws IOException {
        return start(api, online, model, port, monitor, vision, false);
    }

    /** Overload without Google panel data (status flag only). */
    public static WebServer start(JarvisApi api, boolean online, String model, int port,
            HardwareMonitor monitor, VisionHook vision, boolean googleConnected) throws IOException {
        return start(api, online, model, port, monitor, vision, googleConnected, null, null);
    }

    /** Overload without People features. */
    public static WebServer start(JarvisApi api, boolean online, String model, int port,
            HardwareMonitor monitor, VisionHook vision, boolean googleConnected,
            com.jarvis.integrations.google.GoogleWorkspaceService google,
            com.jarvis.memory.MemoryStore<String> memory) throws IOException {
        return start(api, online, model, port, monitor, vision, googleConnected, google, memory,
                null, null, null);
    }

    /**
     * Full wiring. Adds {@code people} → {@code GET/POST /people} and {@code recognizer}
     * (nullable) → {@code POST /recognize} for on-demand webcam face matching,
     * {@code GET/POST /aboutme}, and {@code governance} (nullable) → {@code GET /audit}
     * (audit log) and {@code GET /tools} (tool manifests, risk tiers, health).
     */
    public static WebServer start(JarvisApi api, boolean online, String model, int port,
            HardwareMonitor monitor, VisionHook vision, boolean googleConnected,
            com.jarvis.integrations.google.GoogleWorkspaceService google,
            com.jarvis.memory.MemoryStore<String> memory,
            PeopleStore people, PeopleRecognizer recognizer,
            AppWiring.Governance governance) throws IOException {
        com.jarvis.audit.AuditLog auditLog = governance == null ? null : governance.auditLog();
        com.jarvis.registry.PluginRegistry plugins = governance == null ? null : governance.plugins();
        com.jarvis.security.PermissionBroker permissions =
                governance == null ? null : governance.permissions();
        com.jarvis.security.PermissionPolicy permissionPolicy =
                governance == null ? null : governance.permissionPolicy();
        com.jarvis.updater.UpdateChecker updates =
                governance == null ? null : governance.updates();
        com.jarvis.licensing.LicenseManager license =
                governance == null ? null : governance.license();
        com.jarvis.metering.UsageMeter usage =
                governance == null ? null : governance.usage();
        com.jarvis.tasks.TaskBoard tasks =
                governance == null ? null : governance.tasks();
        WorkflowService workflows = governance == null ? null : governance.workflows();
        com.jarvis.kb.KnowledgeBase knowledge = governance == null ? null : governance.knowledge();
        MultiAgentService agents = governance == null ? null : governance.agents();
        AutonomousService autonomous = governance == null ? null : governance.autonomous();
        SemanticMemoryService semantic = governance == null ? null : governance.semantic();
        DiscussionService discussion = governance == null ? null : governance.discussion();
        BrainVault brain = governance == null ? null : governance.brain();
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(model, "model");
        byte[] page = loadDashboard();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));

        server.createContext("/alerts", exchange -> {
            ArrayNode arr = MAPPER.createArrayNode();
            if (monitor != null) {
                monitor.drainAlerts().forEach(arr::add);
            }
            respond(exchange, 200, "application/json", arr.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/telemetry", exchange -> {
            com.jarvis.integrations.mark.HardwareTool.Sample s =
                    com.jarvis.integrations.mark.HardwareTool.sample();
            ObjectNode t = MAPPER.createObjectNode();
            t.put("cpu", Math.round(s.cpuPercent()));
            t.put("ram", Math.round(s.ramPercent()));
            t.put("ramUsedGb", Math.round(s.ramUsedGb() * 10) / 10.0);
            t.put("ramTotalGb", Math.round(s.ramTotalGb() * 10) / 10.0);
            t.put("cores", s.cores());
            respond(exchange, 200, "application/json", t.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/usage", exchange -> {
            ObjectNode o = MAPPER.createObjectNode();
            ObjectNode summary = o.putObject("summary");
            ArrayNode events = o.putArray("events");
            if (usage != null) {
                com.jarvis.metering.UsageSummary s = usage.summary();
                summary.put("calls", s.calls());
                summary.put("inputTokens", s.inputTokens());
                summary.put("outputTokens", s.outputTokens());
                summary.put("totalTokens", s.totalTokens());
                summary.put("costUsd", s.costUsd());
                int limit = parseInt(param(exchange, "limit", "100"), 100);
                for (com.jarvis.metering.UsageEvent e : usage.recent(limit)) {
                    ObjectNode ev = events.addObject();
                    ev.put("at", e.at().toString());
                    ev.put("provider", e.provider());
                    ev.put("model", e.model());
                    ev.put("inputTokens", e.inputTokens());
                    ev.put("outputTokens", e.outputTokens());
                    ev.put("costUsd", e.costUsd());
                }
            } else {
                summary.put("calls", 0);
                summary.put("inputTokens", 0);
                summary.put("outputTokens", 0);
                summary.put("totalTokens", 0);
                summary.put("costUsd", 0);
            }
            respond(exchange, 200, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/license", exchange -> {
            com.jarvis.licensing.LicenseStatus s = license == null
                    ? com.jarvis.licensing.LicenseStatus.dev() : license.status();
            respond(exchange, 200, "application/json",
                    licenseJson(s).toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/license/activate", exchange -> {
            com.jarvis.licensing.LicenseStatus s = license == null
                    ? com.jarvis.licensing.LicenseStatus.dev() : license.status();
            if (license != null && "POST".equals(exchange.getRequestMethod())) {
                try (InputStream body = exchange.getRequestBody()) {
                    s = license.activate(MAPPER.readTree(body).path("key").asText(""));
                } catch (IOException e) {
                    respond(exchange, 400, "text/plain", "bad json".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            respond(exchange, 200, "application/json",
                    licenseJson(s).toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/license/deactivate", exchange -> {
            com.jarvis.licensing.LicenseStatus s = license == null
                    ? com.jarvis.licensing.LicenseStatus.dev() : license.status();
            if (license != null && "POST".equals(exchange.getRequestMethod())) {
                s = license.deactivate();
            }
            respond(exchange, 200, "application/json",
                    licenseJson(s).toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/update", exchange -> {
            ObjectNode o = MAPPER.createObjectNode();
            if (updates == null) {
                o.put("state", "DISABLED");
                o.put("currentVersion", AppWiring.APP_VERSION);
                o.put("message", "Update checks are off.");
            } else {
                com.jarvis.updater.UpdateStatus s = updates.latest();
                o.put("state", s.state().name());
                o.put("currentVersion", s.currentVersion());
                o.put("message", s.message());
                if (s.available() != null) {
                    o.put("version", s.available().version());
                    o.put("downloadUrl", s.available().downloadUrl());
                    o.put("notes", s.available().notes());
                }
            }
            respond(exchange, 200, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/permissions/pending", exchange -> {
            ArrayNode arr = MAPPER.createArrayNode();
            if (permissions != null) {
                for (com.jarvis.security.PendingPermission p : permissions.pending()) {
                    ObjectNode o = arr.addObject();
                    o.put("id", p.id());
                    o.put("tool", p.tool());
                    o.put("riskTier", p.riskTier().name());
                    o.put("detail", p.detail());
                    o.put("at", p.requestedAtMillis());
                }
            }
            respond(exchange, 200, "application/json", arr.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/permissions/decide", exchange -> {
            boolean ok = false;
            if (permissions != null && "POST".equals(exchange.getRequestMethod())) {
                try (InputStream body = exchange.getRequestBody()) {
                    JsonNode j = MAPPER.readTree(body);
                    ok = permissions.decide(j.path("id").asText(""), j.path("allow").asBoolean(false));
                } catch (IOException e) {
                    respond(exchange, 400, "text/plain", "bad json".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            ObjectNode o = MAPPER.createObjectNode();
            o.put("ok", ok);
            respond(exchange, 200, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/permissions/config", exchange -> {
            if (permissionPolicy != null && "POST".equals(exchange.getRequestMethod())) {
                try (InputStream body = exchange.getRequestBody()) {
                    JsonNode j = MAPPER.readTree(body);
                    try {
                        permissionPolicy.setLevel(
                                com.jarvis.security.PermissionLevel.valueOf(j.path("level").asText("")));
                    } catch (IllegalArgumentException ignored) {
                        // unknown level -> leave unchanged
                    }
                } catch (IOException e) {
                    respond(exchange, 400, "text/plain", "bad json".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            ObjectNode o = MAPPER.createObjectNode();
            o.put("level", permissionPolicy == null ? "OFF" : permissionPolicy.level().name());
            respond(exchange, 200, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/tools", exchange -> {
            ArrayNode arr = MAPPER.createArrayNode();
            if (plugins != null) {
                for (com.jarvis.registry.ToolStats s : plugins.allStats()) {
                    ObjectNode o = arr.addObject();
                    o.put("name", s.name());
                    o.put("description",
                            plugins.manifestFor(s.name()).map(m -> m.description()).orElse(""));
                    o.put("riskTier", s.riskTier().name());
                    o.put("health", s.health().name());
                    o.put("totalCalls", s.totalCalls());
                    o.put("failures", s.failures());
                    o.put("lastError", s.lastError());
                }
            }
            respond(exchange, 200, "application/json", arr.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/audit", exchange -> {
            if (auditLog == null) {
                respond(exchange, 200, "application/json", "[]".getBytes(StandardCharsets.UTF_8));
                return;
            }
            com.jarvis.audit.AuditQuery q = com.jarvis.audit.AuditQuery.all();
            String action = param(exchange, "action", "");
            if (!action.isBlank()) {
                q = q.action(action);
            }
            String risk = param(exchange, "risk", "");
            if (!risk.isBlank()) {
                try {
                    q = q.riskTier(com.jarvis.tools.RiskTier.valueOf(risk));
                } catch (IllegalArgumentException ignored) {
                    // unknown tier -> no risk filter
                }
            }
            String outcome = param(exchange, "outcome", "");
            if (!outcome.isBlank()) {
                try {
                    q = q.outcome(com.jarvis.audit.AuditOutcome.valueOf(outcome));
                } catch (IllegalArgumentException ignored) {
                    // unknown outcome -> no outcome filter
                }
            }
            int limit = parseInt(param(exchange, "limit", "200"), 200);
            java.util.List<com.jarvis.audit.AuditEntry> entries = auditLog.query(q);
            ArrayNode arr = MAPPER.createArrayNode();
            int start = Math.max(0, entries.size() - limit);
            for (int i = entries.size() - 1; i >= start; i--) {   // newest first
                com.jarvis.audit.AuditEntry e = entries.get(i);
                ObjectNode o = arr.addObject();
                o.put("seq", e.seq());
                o.put("at", e.at().toString());
                o.put("category", e.event().category().name());
                o.put("action", e.event().action());
                o.put("trigger", e.event().trigger().name());
                o.put("riskTier", e.event().riskTier().name());
                o.put("outcome", e.event().outcome().name());
                o.put("detail", e.event().detail());
            }
            respond(exchange, 200, "application/json", arr.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/mail", exchange -> {
            if (google == null) {
                respond(exchange, 503, "application/json",
                        "{\"error\":\"Google not connected\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            try {
                String query = param(exchange, "query", "in:inbox");
                int max = parseInt(param(exchange, "max", "12"), 12);
                byte[] body = google.listEmails(query, max).toString().getBytes(StandardCharsets.UTF_8);
                respond(exchange, 200, "application/json", body);
            } catch (Exception e) {
                respond(exchange, 200, "application/json",
                        ("{\"error\":" + jsonStr(e.getMessage()) + "}").getBytes(StandardCharsets.UTF_8));
            }
        });

        server.createContext("/calendar", exchange -> {
            if (google == null) {
                respond(exchange, 503, "application/json",
                        "{\"error\":\"Google not connected\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            try {
                int max = parseInt(param(exchange, "max", "15"), 15);
                byte[] body = google.listEvents(max).toString().getBytes(StandardCharsets.UTF_8);
                respond(exchange, 200, "application/json", body);
            } catch (Exception e) {
                respond(exchange, 200, "application/json",
                        ("{\"error\":" + jsonStr(e.getMessage()) + "}").getBytes(StandardCharsets.UTF_8));
            }
        });

        server.createContext("/instructions", exchange -> {
            String mailKey = "mail";
            String calKey = "calendar";
            String scope = "instructions";
            if ("POST".equals(exchange.getRequestMethod()) && memory != null) {
                try (InputStream body = exchange.getRequestBody()) {
                    JsonNode json = MAPPER.readTree(body);
                    String area = json.path("area").asText("");
                    String text = json.path("text").asText("");
                    if (mailKey.equals(area) || calKey.equals(area)) {
                        memory.put(scope, area, text);
                    }
                } catch (IOException e) {
                    respond(exchange, 400, "text/plain", "bad json".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            ObjectNode out = MAPPER.createObjectNode();
            out.put(mailKey, memory == null ? "" : memory.get(scope, mailKey).map(m -> m.value()).orElse(""));
            out.put(calKey, memory == null ? "" : memory.get(scope, calKey).map(m -> m.value()).orElse(""));
            respond(exchange, 200, "application/json", out.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/people", exchange -> {
            if (people == null) {
                respond(exchange, 200, "application/json", "[]".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())) {
                try (InputStream body = exchange.getRequestBody()) {
                    JsonNode j = MAPPER.readTree(body);
                    if (j.path("delete").asBoolean(false)) {
                        people.delete(j.path("id").asText(""));
                    } else {
                        String name = j.path("name").asText("").strip();
                        String id = j.path("id").asText("").strip();
                        if (!name.isEmpty() && !id.isEmpty()) {
                            people.update(id, name, j.path("relationship").asText(""),
                                    j.path("email").asText(""), j.path("phone").asText(""),
                                    j.path("company").asText(""), j.path("notes").asText(""),
                                    j.path("photo").asText(""));
                        } else if (!name.isEmpty()) {
                            people.add(name, j.path("relationship").asText(""),
                                    j.path("email").asText(""), j.path("phone").asText(""),
                                    j.path("company").asText(""), j.path("notes").asText(""),
                                    j.path("photo").asText(""));
                        }
                    }
                } catch (IOException e) {
                    respond(exchange, 400, "text/plain", "bad json".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            respond(exchange, 200, "application/json",
                    people.summaries().toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/people/photo", exchange -> {
            if (people == null) {
                respond(exchange, 404, "text/plain", "no".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String id = param(exchange, "id", "");
            String dataUrl = people.all().stream().filter(p -> p.id().equals(id))
                    .map(PeopleStore.Person::photo).findFirst().orElse("");
            if (dataUrl.isBlank() || !dataUrl.startsWith("data:")) {
                respond(exchange, 404, "text/plain", "no photo".getBytes(StandardCharsets.UTF_8));
                return;
            }
            int semi = dataUrl.indexOf(';');
            int comma = dataUrl.indexOf(',');
            String mediaType = semi > 5 ? dataUrl.substring(5, semi) : "image/png";
            byte[] img = java.util.Base64.getDecoder().decode(dataUrl.substring(comma + 1));
            exchange.getResponseHeaders().set("Content-Type", mediaType);
            exchange.sendResponseHeaders(200, img.length);
            exchange.getResponseBody().write(img);
            exchange.close();
        });

        server.createContext("/recognize", exchange -> {
            ObjectNode reply = MAPPER.createObjectNode();
            if (recognizer == null || people == null) {
                reply.put("response", "Recognition needs an API key and at least one saved person, sir.");
                respond(exchange, 200, "application/json", reply.toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            try (InputStream body = exchange.getRequestBody()) {
                String image = MAPPER.readTree(body).path("image").asText("");
                java.util.List<PeopleStore.Person> known = people.all().stream()
                        .filter(p -> p.photo() != null && !p.photo().isBlank()).toList();
                if (known.isEmpty()) {
                    reply.put("response", "I don't have any saved people to compare against yet, sir.");
                } else {
                    reply.put("response", recognizer.recognize(image, known));
                }
            } catch (Exception e) {
                reply.put("response", "I couldn't run recognition, sir: " + e.getMessage());
            }
            respond(exchange, 200, "application/json", reply.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/aboutme", exchange -> {
            if ("POST".equals(exchange.getRequestMethod()) && memory != null) {
                try (InputStream body = exchange.getRequestBody()) {
                    memory.put("about", "me", MAPPER.readTree(body).path("text").asText(""));
                } catch (IOException e) {
                    respond(exchange, 400, "text/plain", "bad json".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            ObjectNode out = MAPPER.createObjectNode();
            out.put("text", memory == null ? "" : memory.get("about", "me").map(m -> m.value()).orElse(""));
            respond(exchange, 200, "application/json", out.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/onboarding/complete", exchange -> {
            if ("POST".equals(exchange.getRequestMethod()) && memory != null) {
                memory.put("app", "onboarded", "true");
            }
            ObjectNode out = MAPPER.createObjectNode();
            out.put("completed", true);
            respond(exchange, 200, "application/json", out.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/onboarding", exchange -> {
            boolean completed = memory != null
                    && memory.get("app", "onboarded").map(m -> "true".equals(m.value())).orElse(false);
            ObjectNode out = MAPPER.createObjectNode();
            out.put("completed", completed);
            respond(exchange, 200, "application/json", out.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/autonomous/run", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod()) || autonomous == null) {
                respond(exchange, autonomous == null ? 503 : 405, "text/plain",
                        (autonomous == null ? "autonomous unavailable" : "method not allowed")
                                .getBytes(StandardCharsets.UTF_8));
                return;
            }
            String goal;
            try (InputStream body = exchange.getRequestBody()) {
                goal = MAPPER.readTree(body).path("goal").asText("").strip();
            } catch (IOException e) {
                respond(exchange, 400, "text/plain", "bad json".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (goal.isBlank()) {
                respond(exchange, 400, "text/plain", "empty goal".getBytes(StandardCharsets.UTF_8));
                return;
            }
            com.jarvis.autonomous.AutonomousRunner.AutonomousRun run = autonomous.run(goal);
            ObjectNode o = MAPPER.createObjectNode();
            o.put("goal", run.goal());
            o.put("completed", run.completed());
            ArrayNode steps = o.putArray("steps");
            run.steps().forEach(steps::add);
            respond(exchange, 200, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/agents/run", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod()) || agents == null) {
                respond(exchange, agents == null ? 503 : 405, "text/plain",
                        (agents == null ? "multi-agent unavailable" : "method not allowed")
                                .getBytes(StandardCharsets.UTF_8));
                return;
            }
            String goal;
            try (InputStream body = exchange.getRequestBody()) {
                goal = MAPPER.readTree(body).path("goal").asText("").strip();
            } catch (IOException e) {
                respond(exchange, 400, "text/plain", "bad json".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (goal.isBlank()) {
                respond(exchange, 400, "text/plain", "empty goal".getBytes(StandardCharsets.UTF_8));
                return;
            }
            com.jarvis.agents.MultiAgentManager.Conversation c = agents.run(goal);
            ObjectNode o = MAPPER.createObjectNode();
            o.put("goal", c.goal());
            o.put("result", c.result());
            ArrayNode msgs = o.putArray("messages");
            for (com.jarvis.agents.MultiAgentManager.Message m : c.messages()) {
                ObjectNode mo = msgs.addObject();
                mo.put("role", m.role().name());
                mo.put("content", m.content());
            }
            respond(exchange, 200, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/kb/search", exchange -> {
            ArrayNode arr = MAPPER.createArrayNode();
            if (knowledge != null) {
                int k = parseInt(param(exchange, "k", "5"), 5);
                for (com.jarvis.rag.ScoredDocument s : knowledge.search(param(exchange, "q", ""), k)) {
                    ObjectNode o = arr.addObject();
                    o.put("id", s.document().id());
                    o.put("title", com.jarvis.kb.KnowledgeBase.titleOf(s.document()));
                    o.put("score", s.score());
                    String c = s.document().content();
                    o.put("snippet", c.length() > 200 ? c.substring(0, 200) + "…" : c);
                }
            }
            respond(exchange, 200, "application/json", arr.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/kb", exchange -> {
            if ("POST".equals(exchange.getRequestMethod()) && knowledge != null) {
                try (InputStream body = exchange.getRequestBody()) {
                    JsonNode j = MAPPER.readTree(body);
                    String action = j.path("action").asText("");
                    if ("add".equals(action)) {
                        if (!j.path("content").asText("").isBlank()
                                || !j.path("title").asText("").isBlank()) {
                            knowledge.add(j.path("title").asText(""), j.path("content").asText(""),
                                    System.currentTimeMillis());
                        }
                    } else if ("delete".equals(action)) {
                        knowledge.delete(j.path("id").asText(""));
                    }
                } catch (IOException e) {
                    respond(exchange, 400, "text/plain", "bad json".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            ArrayNode arr = MAPPER.createArrayNode();
            if (knowledge != null) {
                for (com.jarvis.rag.Document d : knowledge.list()) {
                    ObjectNode o = arr.addObject();
                    o.put("id", d.id());
                    o.put("title", com.jarvis.kb.KnowledgeBase.titleOf(d));
                    String c = d.content();
                    o.put("snippet", c.length() > 200 ? c.substring(0, 200) + "…" : c);
                }
            }
            respond(exchange, 200, "application/json", arr.toString().getBytes(StandardCharsets.UTF_8));
        });

        // ---- BRAIN (Obsidian vault, read-only) ----
        server.createContext("/brain/search", exchange -> {
            ArrayNode arr = MAPPER.createArrayNode();
            if (brain != null) {
                int k = parseInt(param(exchange, "k", "8"), 8);
                for (BrainVault.Hit h : brain.search(param(exchange, "q", ""), k)) {
                    ObjectNode o = arr.addObject();
                    o.put("path", h.path());
                    o.put("title", h.title());
                    o.put("score", h.score());
                    o.put("snippet", h.snippet());
                }
            }
            respond(exchange, 200, "application/json", arr.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/brain/cite", exchange -> {
            ArrayNode arr = MAPPER.createArrayNode();
            if (brain != null) {
                int k = parseInt(param(exchange, "k", "3"), 3);
                for (BrainVault.Hit h : brain.cite(param(exchange, "q", ""), k)) {
                    ObjectNode o = arr.addObject();
                    o.put("source", h.title().isBlank() ? h.path() : h.title());
                    o.put("path", h.path());
                    o.put("snippet", h.snippet());
                }
            }
            respond(exchange, 200, "application/json", arr.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/brain/note", exchange -> {
            if (brain == null || !brain.configured()) {
                respond(exchange, 503, "text/plain", "brain unavailable".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = param(exchange, "path", "");
            try {
                String[] note = brain.readNote(path);
                ObjectNode o = MAPPER.createObjectNode();
                o.put("path", path);
                o.put("title", note[0]);
                o.put("markdown", note[1]);   // raw; the client escapes before rendering
                respond(exchange, 200, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
            } catch (BrainVault.VaultAccessException e) {
                String msg = e.getMessage() == null ? "invalid path" : e.getMessage();
                int code;
                if (msg.contains("no such")) {
                    code = 404;                                   // note doesn't exist
                } else if (msg.contains("escape") || msg.contains("traversal")
                        || msg.contains("absolute")) {
                    code = 403;                                   // access forbidden outside the vault
                } else {
                    code = 400;                                   // malformed request
                }
                respond(exchange, code, "text/plain", msg.getBytes(StandardCharsets.UTF_8));
            }
        });

        server.createContext("/brain", exchange -> {
            ObjectNode root = MAPPER.createObjectNode();
            boolean on = brain != null && brain.configured();
            root.put("configured", on);
            root.put("readOnly", brain == null || brain.readOnly());
            root.put("root", on ? brain.rootDisplay() : "");
            root.put("count", on ? brain.count() : 0);
            ArrayNode notes = root.putArray("notes");
            if (on) {
                for (BrainVault.Note n : brain.notes()) {
                    ObjectNode o = notes.addObject();
                    o.put("path", n.path());
                    o.put("title", n.title());
                }
            }
            respond(exchange, 200, "application/json", root.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/discussion/run", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod()) || discussion == null) {
                respond(exchange, discussion == null ? 503 : 405, "text/plain",
                        (discussion == null ? "discussion unavailable" : "method not allowed")
                                .getBytes(StandardCharsets.UTF_8));
                return;
            }
            String topic;
            try (InputStream body = exchange.getRequestBody()) {
                topic = MAPPER.readTree(body).path("topic").asText("").strip();
            } catch (IOException e) {
                respond(exchange, 400, "text/plain", "bad json".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (topic.isBlank()) {
                respond(exchange, 400, "text/plain", "empty topic".getBytes(StandardCharsets.UTF_8));
                return;
            }
            com.jarvis.discussion.DiscussionRunner.Discussion d = discussion.run(topic);
            ObjectNode o = MAPPER.createObjectNode();
            o.put("topic", d.topic());
            o.put("converged", d.converged());
            o.put("outcome", d.outcome());
            o.put("advisorAvailable", discussion.advisorAvailable());
            ArrayNode rounds = o.putArray("rounds");
            d.rounds().forEach(r -> {
                ObjectNode ro = rounds.addObject();
                ro.put("index", r.index());
                ro.put("question", r.question());
                ro.put("answer", r.failed() ? null : r.answer());
                if (r.failed()) {
                    ro.put("error", r.error());
                }
            });
            respond(exchange, 200, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/discussion", exchange -> {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("advisorAvailable", discussion != null && discussion.advisorAvailable());
            ArrayNode arr = root.putArray("items");
            if (discussion != null) {
                int limit = parseInt(param(exchange, "limit", "20"), 20);
                for (String payload : discussion.recent(limit)) {
                    try {
                        arr.add(MAPPER.readTree(payload));
                    } catch (IOException ignore) {
                        // skip an unparseable record
                    }
                }
            }
            respond(exchange, 200, "application/json", root.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/semantic/search", exchange -> {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("mode", semantic != null && semantic.semantic() ? "semantic" : "keyword");
            ArrayNode arr = root.putArray("results");
            if (semantic != null) {
                int k = parseInt(param(exchange, "k", "5"), 5);
                for (com.jarvis.rag.ScoredDocument s : semantic.recall(param(exchange, "q", ""), k)) {
                    ObjectNode o = arr.addObject();
                    o.put("id", s.document().id());
                    o.put("title", SemanticMemoryService.titleOf(s.document()));
                    o.put("score", s.score());
                    String c = s.document().content();
                    o.put("snippet", c.length() > 200 ? c.substring(0, 200) + "…" : c);
                }
            }
            respond(exchange, 200, "application/json", root.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/semantic", exchange -> {
            if ("POST".equals(exchange.getRequestMethod()) && semantic != null) {
                try (InputStream body = exchange.getRequestBody()) {
                    JsonNode j = MAPPER.readTree(body);
                    if ("add".equals(j.path("action").asText(""))
                            && (!j.path("content").asText("").isBlank()
                                    || !j.path("title").asText("").isBlank())) {
                        semantic.remember(j.path("title").asText(""), j.path("content").asText(""),
                                System.currentTimeMillis());
                    }
                } catch (IOException e) {
                    respond(exchange, 400, "text/plain", "bad json".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            ObjectNode root = MAPPER.createObjectNode();
            root.put("mode", semantic != null && semantic.semantic() ? "semantic" : "keyword");
            ArrayNode arr = root.putArray("items");
            if (semantic != null) {
                for (com.jarvis.rag.Document d : semantic.all()) {
                    ObjectNode o = arr.addObject();
                    o.put("id", d.id());
                    o.put("title", SemanticMemoryService.titleOf(d));
                    String c = d.content();
                    o.put("snippet", c.length() > 200 ? c.substring(0, 200) + "…" : c);
                }
            }
            respond(exchange, 200, "application/json", root.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/workflows/runs", exchange -> {
            ArrayNode arr = MAPPER.createArrayNode();
            if (workflows != null) {
                int limit = parseInt(param(exchange, "limit", "50"), 50);
                for (com.jarvis.workflows.WorkflowRun run : workflows.recentRuns(limit)) {
                    ObjectNode o = arr.addObject();
                    o.put("id", run.id());
                    o.put("workflowName", run.workflowName());
                    o.put("startedAtMillis", run.startedAtMillis());
                    o.put("trigger", run.trigger().name());
                    o.put("ok", run.ok());
                    ArrayNode steps = o.putArray("steps");
                    run.steps().forEach(s -> {
                        ObjectNode so = steps.addObject();
                        so.put("step", s.step());
                        so.put("output", s.output());
                        so.put("ok", s.ok());
                    });
                }
            }
            respond(exchange, 200, "application/json", arr.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/workflows", exchange -> {
            if ("POST".equals(exchange.getRequestMethod()) && workflows != null) {
                try (InputStream body = exchange.getRequestBody()) {
                    JsonNode j = MAPPER.readTree(body);
                    String action = j.path("action").asText("");
                    if ("save".equals(action)) {
                        String id = j.path("id").asText("").isBlank()
                                ? "w-" + Long.toHexString(System.nanoTime()) : j.path("id").asText();
                        java.util.List<String> steps = new java.util.ArrayList<>();
                        j.path("steps").forEach(s -> {
                            if (!s.asText().isBlank()) {
                                steps.add(s.asText().strip());
                            }
                        });
                        com.jarvis.workflows.TriggerType trig;
                        try {
                            trig = com.jarvis.workflows.TriggerType.valueOf(
                                    j.path("trigger").asText("MANUAL"));
                        } catch (IllegalArgumentException e) {
                            trig = com.jarvis.workflows.TriggerType.MANUAL;
                        }
                        workflows.save(new com.jarvis.workflows.Workflow(id, j.path("name").asText(""),
                                steps, trig, j.path("intervalSeconds").asLong(0)));
                    } else if ("delete".equals(action)) {
                        workflows.delete(j.path("id").asText(""));
                    } else if ("run".equals(action)) {
                        workflows.run(j.path("id").asText(""),
                                com.jarvis.workflows.TriggerType.MANUAL);
                    }
                } catch (IOException e) {
                    respond(exchange, 400, "text/plain", "bad json".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            ArrayNode arr = MAPPER.createArrayNode();
            if (workflows != null) {
                for (com.jarvis.workflows.Workflow w : workflows.list()) {
                    ObjectNode o = arr.addObject();
                    o.put("id", w.id());
                    o.put("name", w.name());
                    o.put("trigger", w.trigger().name());
                    o.put("intervalSeconds", w.intervalSeconds());
                    ArrayNode steps = o.putArray("steps");
                    w.steps().forEach(steps::add);
                }
            }
            respond(exchange, 200, "application/json", arr.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/tasks", exchange -> {
            if ("POST".equals(exchange.getRequestMethod()) && tasks != null) {
                try (InputStream body = exchange.getRequestBody()) {
                    JsonNode j = MAPPER.readTree(body);
                    String action = j.path("action").asText("");
                    if ("create".equals(action)) {
                        java.util.List<String> deps = new java.util.ArrayList<>();
                        j.path("dependsOn").forEach(d -> deps.add(d.asText()));
                        tasks.create(j.path("title").asText("").strip(),
                                j.path("notes").asText(""), deps, System.currentTimeMillis());
                    } else if ("move".equals(action)) {
                        try {
                            tasks.move(j.path("id").asText(""), com.jarvis.tasks.TaskStatus.valueOf(
                                    j.path("status").asText("TODO")));
                        } catch (IllegalArgumentException ignored) {
                            // unknown status -> no move
                        }
                    } else if ("delete".equals(action)) {
                        tasks.delete(j.path("id").asText(""));
                    }
                } catch (IOException e) {
                    respond(exchange, 400, "text/plain", "bad json".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            ArrayNode arr = MAPPER.createArrayNode();
            if (tasks != null) {
                java.util.List<com.jarvis.tasks.Task> all = tasks.list();
                for (com.jarvis.tasks.Task t : all) {
                    ObjectNode o = arr.addObject();
                    o.put("id", t.id());
                    o.put("title", t.title());
                    o.put("notes", t.notes());
                    o.put("status", t.status().name());
                    o.put("blocked", com.jarvis.tasks.TaskBoard.isBlocked(t, all));
                    ArrayNode deps = o.putArray("dependsOn");
                    t.dependsOn().forEach(deps::add);
                }
            }
            respond(exchange, 200, "application/json", arr.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/memory", exchange -> {
            if ("POST".equals(exchange.getRequestMethod()) && memory != null) {
                try (InputStream body = exchange.getRequestBody()) {
                    JsonNode j = MAPPER.readTree(body);
                    String action = j.path("action").asText("");
                    if ("add".equals(action)) {
                        String value = j.path("value").asText("").strip();
                        if (!value.isEmpty()) {
                            memory.put("preferences",
                                    "pref-" + Long.toHexString(System.nanoTime()), value);
                        }
                    } else if ("delete".equals(action)) {
                        memory.delete("preferences", j.path("key").asText(""));
                    }
                } catch (IOException e) {
                    respond(exchange, 400, "text/plain", "bad json".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            ObjectNode out = MAPPER.createObjectNode();
            ArrayNode prefs = out.putArray("preferences");
            ArrayNode rem = out.putArray("reminders");
            if (memory != null) {
                memory.query("preferences").stream()
                        .sorted(java.util.Comparator.comparing(e -> e.key()))
                        .forEach(e -> {
                            ObjectNode o = prefs.addObject();
                            o.put("key", e.key());
                            o.put("value", e.value());
                        });
                memory.query("reminders").stream()
                        .sorted(java.util.Comparator.comparing(e -> e.key()))
                        .forEach(e -> {
                            ObjectNode o = rem.addObject();
                            o.put("key", e.key());
                            o.put("value", e.value());
                        });
            }
            out.put("about", memory == null ? ""
                    : memory.get("about", "me").map(m -> m.value()).orElse(""));
            ObjectNode instr = out.putObject("instructions");
            instr.put("mail", memory == null ? ""
                    : memory.get("instructions", "mail").map(m -> m.value()).orElse(""));
            instr.put("calendar", memory == null ? ""
                    : memory.get("instructions", "calendar").map(m -> m.value()).orElse(""));
            respond(exchange, 200, "application/json", out.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/config", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                try (InputStream body = exchange.getRequestBody()) {
                    JsonNode json = MAPPER.readTree(body);
                    if (monitor != null && json.has("cpuThreshold") && json.has("ramThreshold")) {
                        monitor.setThresholds(json.get("cpuThreshold").asDouble(),
                                json.get("ramThreshold").asDouble());
                    }
                } catch (IOException e) {
                    respond(exchange, 400, "text/plain", "bad json".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            ObjectNode cfg = MAPPER.createObjectNode();
            cfg.put("cpuThreshold", monitor == null ? 90 : monitor.cpuThreshold());
            cfg.put("ramThreshold", monitor == null ? 90 : monitor.ramThreshold());
            respond(exchange, 200, "application/json", cfg.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/vision", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "text/plain", "method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (vision == null) {
                respond(exchange, 503, "application/json",
                        "{\"response\":\"Vision needs an API key, sir.\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String imageB64;
            String question;
            try (InputStream body = exchange.getRequestBody()) {
                JsonNode json = MAPPER.readTree(body);
                imageB64 = json.path("image").asText("");
                question = json.path("question").asText("What do you see?");
            } catch (IOException e) {
                respond(exchange, 400, "text/plain", "bad json".getBytes(StandardCharsets.UTF_8));
                return;
            }
            ObjectNode reply = MAPPER.createObjectNode();
            try {
                byte[] png = java.util.Base64.getDecoder().decode(imageB64);
                reply.put("response", vision.analyze(png, question));
            } catch (Exception e) {
                reply.put("response", "I couldn't analyze that image, sir: " + e.getMessage());
            }
            respond(exchange, 200, "application/json", reply.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "text/plain", "method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(exchange, 200, "text/html; charset=utf-8", page);
        });

        server.createContext("/status", exchange -> {
            ObjectNode status = MAPPER.createObjectNode();
            status.put("online", online);
            status.put("model", model);
            status.put("google", googleConnected);
            respond(exchange, 200, "application/json", status.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/chat", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "text/plain", "method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String prompt;
            String mode;
            try (InputStream body = exchange.getRequestBody()) {
                JsonNode json = MAPPER.readTree(body);
                prompt = json.path("prompt").asText("");
                mode = json.path("mode").asText("");
            } catch (IOException e) {
                respond(exchange, 400, "text/plain", "bad json".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (prompt.isBlank()) {
                respond(exchange, 400, "text/plain", "empty prompt".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String preamble = modePreamble(mode);
            String effective = preamble.isEmpty() ? prompt.strip() : preamble + "\n\n" + prompt.strip();
            ChatResponse chat = api.chat(new ChatRequest("dashboard", effective));
            ObjectNode reply = MAPPER.createObjectNode();
            reply.put("completed", chat.completed());
            reply.put("response", chat.completed()
                    ? chat.response()
                    : "(no answer - my step budget ran out; please try rephrasing)");
            reply.put("toolSteps", chat.toolSteps());
            respond(exchange, 200, "application/json", reply.toString().getBytes(StandardCharsets.UTF_8));
        });

        server.start();
        return new WebServer(server);
    }

    /** The port the server is actually listening on. */
    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    private static byte[] loadDashboard() throws IOException {
        try (InputStream in = WebServer.class.getResourceAsStream("/dashboard.html")) {
            if (in == null) {
                throw new IOException("dashboard.html missing from classpath");
            }
            return in.readAllBytes();
        }
    }

    private static String param(HttpExchange exchange, String key, String dflt) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return dflt;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                if (k.equals(key)) {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        }
        return dflt;
    }

    private static int parseInt(String s, int dflt) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** Maps a Conversations chat mode to an instruction prepended to the user's message. */
    private static String modePreamble(String mode) {
        return switch (mode == null ? "" : mode.toLowerCase(java.util.Locale.ROOT)) {
            case "compose" -> "Mode: Compose. Help the user write — produce polished, well-structured"
                    + " prose or copy for the request below.";
            case "research" -> "Mode: Research. Research the request below thoroughly and answer with"
                    + " organized findings; note sources if you looked anything up.";
            case "execute" -> "Mode: Execute. Act on the request below using your tools where"
                    + " appropriate, and confirm concisely what you did.";
            case "debug" -> "Mode: Debug. Treat the request below as a technical problem: reason step by"
                    + " step, identify likely causes, and give concrete fixes.";
            case "brainstorm" -> "Mode: Brainstorm. Offer several creative options and ideas for the"
                    + " request below; think broadly.";
            default -> "";
        };
    }

    private static ObjectNode licenseJson(com.jarvis.licensing.LicenseStatus s) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("state", s.state().name());
        o.put("locked", s.isLocked());
        o.put("message", s.message());
        if (s.license() != null) {
            o.put("licensee", s.license().licensee());
            o.put("edition", s.license().edition());
            o.put("expiresAt", s.license().expiresAt() == null
                    ? null : s.license().expiresAt().toString());
        }
        return o;
    }

    private static String jsonStr(String s) {
        return MAPPER.getNodeFactory().textNode(s == null ? "" : s).toString();
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
