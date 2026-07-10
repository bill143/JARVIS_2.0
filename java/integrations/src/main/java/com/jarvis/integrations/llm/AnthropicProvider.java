package com.jarvis.integrations.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Objects;

/**
 * {@link LlmProvider} backed by the Anthropic Messages API. It owns the Anthropic wire format:
 * it serializes a neutral {@link Request} into the Messages request body, sends it over an
 * {@link AnthropicPolicy.LlmTransport}, and parses the response back into a {@link Result}
 * (first text block + reported token usage).
 *
 * <p>The transport seam is kept here so retry/backoff and the real JDK {@code HttpClient} transport
 * ({@link AnthropicPolicy#anthropicTransport}) are shared with the rest of the Anthropic callers
 * (vision, people recognition) and so tests can fake the API without network access.
 */
public final class AnthropicProvider implements LlmProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AnthropicPolicy.LlmTransport transport;

    public AnthropicProvider(AnthropicPolicy.LlmTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public Result complete(Request request) throws IOException, InterruptedException {
        return parse(transport.complete(serialize(request)));
    }

    /** Serializes a neutral request into the Anthropic Messages request body. */
    static String serialize(Request request) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", request.model());
        root.put("max_tokens", request.maxTokens());
        root.put("system", request.system());
        ArrayNode messages = root.putArray("messages");
        for (Message m : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", "assistant".equals(m.role()) ? "assistant" : "user");
            node.put("content", m.content());
        }
        return root.toString();
    }

    /** Parses a Messages API response into text + token usage. */
    Result parse(String responseJson) {
        JsonNode root;
        try {
            root = MAPPER.readTree(responseJson);
        } catch (IOException e) {
            throw new IllegalStateException("unparseable API response", e);
        }
        String text = firstText(root, responseJson);
        JsonNode usage = root.path("usage");
        return new Result(text, usage.path("input_tokens").asLong(0),
                usage.path("output_tokens").asLong(0));
    }

    /** Pulls the first text block out of a Messages API response; exposed for tests. */
    static String extractText(String responseJson) {
        JsonNode root;
        try {
            root = MAPPER.readTree(responseJson);
        } catch (IOException e) {
            throw new IllegalStateException("unparseable API response", e);
        }
        return firstText(root, responseJson);
    }

    private static String firstText(JsonNode root, String raw) {
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                return block.path("text").asText();
            }
        }
        throw new IllegalStateException("no text block in response: " + raw);
    }
}
