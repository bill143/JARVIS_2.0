package com.jarvis.integrations.mark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VisionToolTest {

    private static final byte[] FAKE_PNG = {1, 2, 3};
    private static final String OK_RESPONSE = """
            {"content":[{"type":"text","text":"I can see a spreadsheet, sir."}]}""";

    @Test
    void capturesEncodesAndDescribes() {
        AtomicReference<String> sentRequest = new AtomicReference<>();
        VisionTool tool = new VisionTool(request -> {
            sentRequest.set(request);
            return OK_RESPONSE;
        }, "test-model", () -> FAKE_PNG);

        ToolResult result = tool.execute(ToolCall.of("screen_look"));
        assertTrue(result.success());
        assertEquals("I can see a spreadsheet, sir.", result.output());
        assertTrue(sentRequest.get().contains("\"data\":\"AQID\""));  // base64 of {1,2,3}
        assertTrue(sentRequest.get().contains("image/png"));
        assertTrue(sentRequest.get().contains("Describe what is on this screen"));
    }

    @Test
    void customQuestionIsForwarded() {
        AtomicReference<String> sentRequest = new AtomicReference<>();
        VisionTool tool = new VisionTool(request -> {
            sentRequest.set(request);
            return OK_RESPONSE;
        }, "test-model", () -> FAKE_PNG);

        tool.execute(new ToolCall("screen_look", Map.of("question", "what's this error?")));
        assertTrue(sentRequest.get().contains("what's this error?"));
    }

    @Test
    void captureFailureIsGraceful() {
        VisionTool tool = new VisionTool(request -> OK_RESPONSE, "m", () -> {
            throw new IllegalStateException("no display available");
        });

        ToolResult result = tool.execute(ToolCall.of("screen_look"));
        assertFalse(result.success());
        assertTrue(result.error().contains("could not capture"));
    }

    @Test
    void apiFailureIsGraceful() {
        VisionTool tool = new VisionTool(request -> {
            throw new IOException("529 overloaded");
        }, "m", () -> FAKE_PNG);

        ToolResult result = tool.execute(ToolCall.of("screen_look"));
        assertFalse(result.success());
        assertTrue(result.error().contains("vision request failed"));
    }

    @Test
    void toolIdentity() {
        VisionTool tool = new VisionTool(request -> OK_RESPONSE, "m", () -> FAKE_PNG);
        assertEquals("screen_look", tool.name());
        assertTrue(tool.description().contains("screenshot"));
    }
}
