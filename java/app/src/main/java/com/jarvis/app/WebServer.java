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
        return start(api, online, model, port, null, null);
    }

    /**
     * Full wiring: {@code monitor} (nullable) drives {@code GET /alerts}; {@code vision} (nullable)
     * drives {@code POST /vision} for webcam frames.
     */
    public static WebServer start(JarvisApi api, boolean online, String model, int port,
            HardwareMonitor monitor, VisionHook vision) throws IOException {
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

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
