package com.jarvis.integrations.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.agent.loop.AgentContext;
import com.jarvis.agent.loop.AgentPolicy;
import com.jarvis.agent.loop.AgentStep;
import com.jarvis.agent.loop.Decision;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * {@link AgentPolicy} backed by the Anthropic Messages API: the reasoning engine behind the policy
 * seam. Uses only whitelisted machinery — Jackson for JSON and the JDK's built-in
 * {@link HttpClient} for transport.
 *
 * <p>When constructed with a {@link ToolRegistry}, the system prompt advertises the available
 * tools and a one-line invocation protocol ({@code TOOL: <name> <json-args>}). A reply matching
 * that protocol becomes a {@link Decision.Invoke}; the loop feeds the observation back and prior
 * tool results are replayed into the next request, so multi-step tool use works without the
 * native tool-use API.
 *
 * <p>Failures (network, HTTP status, malformed JSON) never propagate out of the loop: they are
 * converted to an apologetic {@code Respond} so an interactive session survives a bad call.
 */
public final class AnthropicPolicy implements AgentPolicy {

    /** Transport seam so tests can fake the API without network access. */
    @FunctionalInterface
    public interface LlmTransport {
        /** Sends the request JSON to the API and returns the response JSON. */
        String complete(String requestJson) throws IOException, InterruptedException;
    }

    private static final String TOOL_MARKER = "TOOL:";
    private static final String BASE_SYSTEM_PROMPT =
            "You are JARVIS, a concise and helpful personal assistant in the style of Tony Stark's"
                    + " AI. Address the user as 'sir'. Answer briefly and never mix languages"
                    + " within one reply.";

    private final LlmTransport transport;
    private final String model;
    private final int maxTokens;
    private final ToolRegistry tools;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile java.util.function.Supplier<String> memoryContext = () -> "";

    /** Creates a policy with no tool access. */
    public AnthropicPolicy(LlmTransport transport, String model, int maxTokens) {
        this(transport, model, maxTokens, null);
    }

    /** Creates a policy that may invoke tools from {@code tools} (nullable = no tools). */
    public AnthropicPolicy(LlmTransport transport, String model, int maxTokens, ToolRegistry tools) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.model = Objects.requireNonNull(model, "model");
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be at least 1, got " + maxTokens);
        }
        this.maxTokens = maxTokens;
        this.tools = tools;
    }

    /** Creates a tool-less policy that calls the real Anthropic API with {@code apiKey}. */
    public static AnthropicPolicy withApiKey(String apiKey, String model) {
        return withApiKey(apiKey, model, null);
    }

    /**
     * Wraps {@code delegate} with Mark-XLVIII-style exponential backoff: transient
     * {@link IOException}s are retried after the given delays; other failures pass through.
     * The sleeper is a seam so tests run instantly.
     */
    static LlmTransport withRetry(LlmTransport delegate, long[] delaysMillis, Sleeper sleeper) {
        Objects.requireNonNull(delegate, "delegate");
        return requestJson -> {
            IOException last = null;
            for (int attempt = 0; attempt <= delaysMillis.length; attempt++) {
                if (attempt > 0) {
                    sleeper.sleep(delaysMillis[attempt - 1]);
                }
                try {
                    return delegate.complete(requestJson);
                } catch (IOException e) {
                    last = e;
                }
            }
            throw last;
        };
    }

    /** Sleep seam for {@link #withRetry}. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** Creates a tool-aware policy that calls the real Anthropic API with {@code apiKey}. */
    public static AnthropicPolicy withApiKey(String apiKey, String model, ToolRegistry tools) {
        LlmTransport retrying = withRetry(
                anthropicTransport(apiKey), new long[] {1_000, 2_000, 4_000}, Thread::sleep);
        return new AnthropicPolicy(retrying, model, 1024, tools);
    }

    /**
     * Installs a supplier of durable user context (preferences, projects) that is injected into
     * every system prompt, so JARVIS "remembers" across independent conversations. Returns this.
     */
    public AnthropicPolicy withMemoryContext(java.util.function.Supplier<String> memoryContext) {
        this.memoryContext = Objects.requireNonNull(memoryContext, "memoryContext");
        return this;
    }

    /** Real Messages API transport for {@code apiKey}; reusable by other API callers (vision). */
    public static LlmTransport anthropicTransport(String apiKey) {
        Objects.requireNonNull(apiKey, "apiKey");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        return requestJson -> {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("https://api.anthropic.com/v1/messages"))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException(
                        "Anthropic API returned " + response.statusCode() + ": " + response.body());
            }
            return response.body();
        };
    }

    @Override
    public Decision decide(AgentContext context) {
        String text;
        try {
            text = extractText(transport.complete(buildRequest(context))).strip();
        } catch (IOException | RuntimeException e) {
            return new Decision.Respond("Sorry — I couldn't reach my language model ("
                    + e.getMessage() + "). Please try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Decision.Respond("Sorry — the request was interrupted. Please try again.");
        }
        if (tools != null && text.startsWith(TOOL_MARKER)) {
            Decision invoke = parseToolLine(text);
            if (invoke != null) {
                return invoke;
            }
        }
        return new Decision.Respond(text);
    }

    /** Parses {@code TOOL: name {json}}; returns null (fall back to Respond) if malformed. */
    private Decision parseToolLine(String text) {
        String line = text.lines().findFirst().orElse("").substring(TOOL_MARKER.length()).strip();
        int brace = line.indexOf('{');
        try {
            String name = (brace < 0 ? line : line.substring(0, brace)).strip();
            Map<String, Object> args = brace < 0
                    ? Map.of()
                    : mapper.readValue(line.substring(brace), new TypeReference<>() {});
            if (name.isEmpty()) {
                return null;
            }
            return new Decision.Invoke(new ToolCall(name, args));
        } catch (IOException e) {
            return null;
        }
    }

    /** Builds the Messages API request body for {@code context}; exposed for tests. */
    String buildRequest(AgentContext context) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);
        root.put("system", systemPrompt());
        ObjectNode message = root.putArray("messages").addObject();
        message.put("role", "user");
        message.put("content", userContent(context));
        return root.toString();
    }

    private String systemPrompt() {
        StringBuilder prompt = new StringBuilder(BASE_SYSTEM_PROMPT);
        String recalled = memoryContext.get();
        if (recalled != null && !recalled.isBlank()) {
            prompt.append("\n\n[What you remember about the user]\n").append(recalled.strip());
        }
        if (tools == null || tools.list().isEmpty()) {
            return prompt.toString();
        }
        prompt.append("\n\nYou can use tools. To use one, reply with EXACTLY one line and"
                + " nothing else:\nTOOL: <name> <json-arguments>\n"
                + "Example: TOOL: clock {\"zone\":\"America/Chicago\"}\n"
                + "After you receive the tool result you may answer normally"
                + " or use another tool.\nAvailable tools:");
        for (Tool tool : tools.list()) {
            prompt.append("\n- ").append(tool.name()).append(": ").append(tool.description());
        }
        return prompt.toString();
    }

    private String userContent(AgentContext context) {
        if (context.steps().isEmpty()) {
            return context.input();
        }
        StringBuilder content = new StringBuilder(context.input());
        content.append("\n\n[Tool results so far]");
        for (AgentStep step : context.steps()) {
            content.append("\n").append(step.call().toolName()).append(" -> ")
                    .append(step.result().success()
                            ? step.result().output()
                            : "ERROR: " + step.result().error());
        }
        return content.toString();
    }

    /** Pulls the first text block out of a Messages API response; exposed for tests. */
    String extractText(String responseJson) {
        try {
            JsonNode content = mapper.readTree(responseJson).path("content");
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    return block.path("text").asText();
                }
            }
            throw new IllegalStateException("no text block in response: " + responseJson);
        } catch (IOException e) {
            throw new IllegalStateException("unparseable API response", e);
        }
    }
}
