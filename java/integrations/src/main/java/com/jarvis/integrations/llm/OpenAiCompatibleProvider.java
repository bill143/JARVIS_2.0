package com.jarvis.integrations.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * {@link LlmProvider} backed by any OpenAI-compatible {@code /chat/completions} endpoint. A single
 * implementation covers a whole family of providers — NVIDIA API Catalog, OpenAI, Groq, OpenRouter,
 * DeepSeek, xAI, Mistral, and local servers (Ollama, LM Studio, vLLM) — since they share the same
 * wire format. Only the base URL and API key differ, so those are the transport's concern.
 *
 * <p>Whitelist-clean: Jackson + the JDK {@link HttpClient} only, mirroring {@link AnthropicProvider}.
 */
public final class OpenAiCompatibleProvider implements LlmProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AnthropicPolicy.LlmTransport transport;

    public OpenAiCompatibleProvider(AnthropicPolicy.LlmTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public Result complete(Request request) throws IOException, InterruptedException {
        return parse(transport.complete(serialize(request)));
    }

    /** Serializes a neutral request into an OpenAI chat-completions body (system as a message). */
    static String serialize(Request request) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", request.model());
        root.put("max_tokens", request.maxTokens());
        ArrayNode messages = root.putArray("messages");
        if (request.system() != null && !request.system().isBlank()) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", request.system());
        }
        for (Message m : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", "assistant".equals(m.role()) ? "assistant" : "user");
            node.put("content", m.content());
        }
        return root.toString();
    }

    /** Parses an OpenAI chat-completions response into text + token usage. */
    Result parse(String responseJson) {
        JsonNode root;
        try {
            root = MAPPER.readTree(responseJson);
        } catch (IOException e) {
            throw new IllegalStateException("unparseable API response", e);
        }
        JsonNode message = root.path("choices").path(0).path("message").path("content");
        if (message.isMissingNode() || !message.isTextual()) {
            throw new IllegalStateException("no message content in response: " + responseJson);
        }
        JsonNode usage = root.path("usage");
        return new Result(message.asText(),
                usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0));
    }

    /**
     * Real transport for an OpenAI-compatible endpoint at {@code baseUrl} (e.g.
     * {@code https://integrate.api.nvidia.com/v1}) authenticated with {@code apiKey}. Posts to
     * {@code <baseUrl>/chat/completions}. The key is never logged.
     */
    public static AnthropicPolicy.LlmTransport transport(String baseUrl, String apiKey) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(apiKey, "apiKey");
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        return requestJson -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("model API returned " + response.statusCode() + ": "
                        + response.body());
            }
            return response.body();
        };
    }
}
