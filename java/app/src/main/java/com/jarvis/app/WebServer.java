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
                null, null);
    }

    /**
     * Full wiring. Adds {@code people} → {@code GET/POST /people} and {@code recognizer}
     * (nullable) → {@code POST /recognize} for on-demand webcam face matching, plus
     * {@code GET/POST /aboutme}.
     */
    public static WebServer start(JarvisApi api, boolean online, String model, int port,
            HardwareMonitor monitor, VisionHook vision, boolean googleConnected,
            com.jarvis.integrations.google.GoogleWorkspaceService google,
            com.jarvis.memory.MemoryStore<String> memory,
            PeopleStore people, PeopleRecognizer recognizer) throws IOException {
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
                        if (!name.isEmpty()) {
                            people.add(name, j.path("relationship").asText(""),
                                    j.path("notes").asText(""), j.path("photo").asText(""));
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
            try (InputStream body = exchange.getRequestBody()) {
                JsonNode json = MAPPER.readTree(body);
                prompt = json.path("prompt").asText("");
            } catch (IOException e) {
                respond(exchange, 400, "text/plain", "bad json".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (prompt.isBlank()) {
                respond(exchange, 400, "text/plain", "empty prompt".getBytes(StandardCharsets.UTF_8));
                return;
            }
            ChatResponse chat = api.chat(new ChatRequest("dashboard", prompt.strip()));
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
