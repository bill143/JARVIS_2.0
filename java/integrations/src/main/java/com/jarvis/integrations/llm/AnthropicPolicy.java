package com.jarvis.integrations.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.agent.loop.AgentContext;
import com.jarvis.agent.loop.AgentPolicy;
import com.jarvis.agent.loop.Decision;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * {@link AgentPolicy} backed by the Anthropic Messages API: the first real reasoning engine behind
 * the policy seam. Uses only whitelisted machinery — Jackson for JSON and the JDK's built-in
 * {@link HttpClient} for transport.
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

    private static final String SYSTEM_PROMPT =
            "You are JARVIS, a concise and helpful personal assistant. Answer briefly.";

    private final LlmTransport transport;
    private final String model;
    private final int maxTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnthropicPolicy(LlmTransport transport, String model, int maxTokens) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.model = Objects.requireNonNull(model, "model");
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be at least 1, got " + maxTokens);
        }
        this.maxTokens = maxTokens;
    }

    /** Creates a policy that calls the real Anthropic API with {@code apiKey}. */
    public static AnthropicPolicy withApiKey(String apiKey, String model) {
        Objects.requireNonNull(apiKey, "apiKey");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        LlmTransport transport = requestJson -> {
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
        return new AnthropicPolicy(transport, model, 1024);
    }

    @Override
    public Decision decide(AgentContext context) {
        try {
            String responseJson = transport.complete(buildRequest(context));
            return new Decision.Respond(extractText(responseJson));
        } catch (IOException | RuntimeException e) {
            return new Decision.Respond("Sorry — I couldn't reach my language model ("
                    + e.getMessage() + "). Please try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Decision.Respond("Sorry — the request was interrupted. Please try again.");
        }
    }

    /** Builds the Messages API request body for {@code context}; exposed for tests. */
    String buildRequest(AgentContext context) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);
        root.put("system", SYSTEM_PROMPT);
        ObjectNode message = root.putArray("messages").addObject();
        message.put("role", "user");
        message.put("content", context.input());
        return root.toString();
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
