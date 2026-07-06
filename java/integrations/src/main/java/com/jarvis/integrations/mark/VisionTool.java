package com.jarvis.integrations.mark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.integrations.llm.AnthropicPolicy.LlmTransport;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Objects;
import javax.imageio.ImageIO;

/**
 * Screen vision, the Mark-XLVIII "look at my screen" capability (ideas only, original code):
 * captures the primary display, sends it to the Anthropic vision API, and returns what the model
 * sees. Screenshots go straight to the API and are never written to disk.
 */
public final class VisionTool implements Tool {

    /** Capture seam so tests run headless. */
    @FunctionalInterface
    public interface Screenshotter {
        byte[] capturePng() throws Exception;
    }

    private final LlmTransport transport;
    private final String model;
    private final Screenshotter screenshotter;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Production wiring: AWT Robot capture of the primary screen. */
    public VisionTool(LlmTransport transport, String model) {
        this(transport, model, VisionTool::captureScreen);
    }

    public VisionTool(LlmTransport transport, String model, Screenshotter screenshotter) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.model = Objects.requireNonNull(model, "model");
        this.screenshotter = Objects.requireNonNull(screenshotter, "screenshotter");
    }

    @Override
    public String name() {
        return "screen_look";
    }

    @Override
    public String description() {
        return "Take a screenshot of the user's screen and describe or analyze it. "
                + "Args: question (optional - what to look for).";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        byte[] png;
        try {
            png = screenshotter.capturePng();
        } catch (Exception e) {
            return ToolResult.error("could not capture the screen: " + e.getMessage());
        }
        Object rawQuestion = call.arguments().get("question");
        String question = rawQuestion == null || String.valueOf(rawQuestion).isBlank()
                ? "Describe what is on this screen, briefly."
                : String.valueOf(rawQuestion);
        try {
            return ToolResult.ok(analyze(png, question));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("vision request interrupted");
        } catch (Exception e) {
            return ToolResult.error("vision request failed: " + e.getMessage());
        }
    }

    /**
     * Analyzes an externally-supplied PNG (e.g. a webcam frame captured by the browser) against
     * {@code question}. Used by the dashboard's webcam endpoint.
     */
    public String analyze(byte[] png, String question) throws Exception {
        return extractText(transport.complete(buildRequest(png, question)));
    }

    /** Builds the vision request body; exposed for tests. */
    String buildRequest(byte[] png, String question) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", 1024);
        ObjectNode message = root.putArray("messages").addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        ObjectNode image = content.addObject();
        image.put("type", "image");
        ObjectNode source = image.putObject("source");
        source.put("type", "base64");
        source.put("media_type", "image/png");
        source.put("data", Base64.getEncoder().encodeToString(png));
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", question);
        return root.toString();
    }

    private String extractText(String responseJson) throws Exception {
        JsonNode content = mapper.readTree(responseJson).path("content");
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                return block.path("text").asText();
            }
        }
        throw new IllegalStateException("no text block in vision response");
    }

    private static byte[] captureScreen() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("no display available (headless environment)");
        }
        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        BufferedImage image = new Robot().createScreenCapture(screen);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
