package com.jarvis.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.integrations.llm.AnthropicPolicy.LlmTransport;
import java.util.List;
import java.util.Objects;

/**
 * On-demand face matching via the vision model: sends a live webcam capture plus each known
 * person's reference photo in one request and asks the model who it is. Not biometric face
 * recognition — smart visual matching against a small, user-curated set.
 */
public final class PeopleRecognizer {

    private final LlmTransport transport;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    public PeopleRecognizer(LlmTransport transport, String model) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.model = Objects.requireNonNull(model, "model");
    }

    /** Returns a natural sentence identifying the person in {@code liveDataUrl}. */
    public String recognize(String liveDataUrl, List<PeopleStore.Person> people) throws Exception {
        String response = transport.complete(buildRequest(liveDataUrl, people));
        return extractText(response);
    }

    /** Builds the multi-image Messages request; package-visible for tests. */
    String buildRequest(String liveDataUrl, List<PeopleStore.Person> people) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", 300);
        ObjectNode message = root.putArray("messages").addObject();
        message.put("role", "user");
        ArrayNode blocks = message.putArray("content");
        addImage(blocks, liveDataUrl);
        text(blocks, "The image above is a LIVE webcam capture. Below are photos of people I know.");
        int i = 1;
        for (PeopleStore.Person p : people) {
            if (p.photo() == null || p.photo().isBlank()) {
                continue;
            }
            addImage(blocks, p.photo());
            text(blocks, "Known person #" + (i++) + ": " + p.name()
                    + (p.relationship().isBlank() ? "" : " (" + p.relationship() + ")")
                    + (p.notes().isBlank() ? "" : ". Notes: " + p.notes()));
        }
        text(blocks, "Who is the person in the LIVE capture? If they match one of the known people, "
                + "reply exactly like: 'That's <name>, <relationship>, sir.' then one short sentence "
                + "about what they're doing. If there is no confident match, briefly describe them and "
                + "say you don't recognise them.");
        return root.toString();
    }

    private void addImage(ArrayNode blocks, String dataUrl) {
        String mediaType = "image/png";
        String data = dataUrl;
        if (dataUrl.startsWith("data:")) {
            int semi = dataUrl.indexOf(';');
            int comma = dataUrl.indexOf(',');
            if (semi > 5 && comma > semi) {
                mediaType = dataUrl.substring(5, semi);
                data = dataUrl.substring(comma + 1);
            }
        }
        ObjectNode image = blocks.addObject();
        image.put("type", "image");
        ObjectNode source = image.putObject("source");
        source.put("type", "base64");
        source.put("media_type", mediaType);
        source.put("data", data);
    }

    private void text(ArrayNode blocks, String value) {
        blocks.addObject().put("type", "text").put("text", value);
    }

    private String extractText(String responseJson) throws Exception {
        JsonNode content = mapper.readTree(responseJson).path("content");
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                return block.path("text").asText();
            }
        }
        throw new IllegalStateException("no text block in recognition response");
    }
}
