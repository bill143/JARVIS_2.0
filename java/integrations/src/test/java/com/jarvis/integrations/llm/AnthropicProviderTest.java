package com.jarvis.integrations.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.agent.loop.AgentContext;
import com.jarvis.agent.loop.Decision;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnthropicProviderTest {

    private static final String RESPONSE_WITH_USAGE = """
            {"content":[{"type":"text","text":"Right away, sir."}],
             "usage":{"input_tokens":42,"output_tokens":7}}""";

    @Test
    void completeReturnsTextAndReportedTokenUsage() throws Exception {
        AnthropicProvider provider = new AnthropicProvider(request -> RESPONSE_WITH_USAGE);

        LlmProvider.Result result = provider.complete(new LlmProvider.Request(
                "m", "sys", List.of(new LlmProvider.Message("user", "hi")), 100));

        assertEquals("Right away, sir.", result.text());
        assertEquals(42, result.inputTokens());
        assertEquals(7, result.outputTokens());
    }

    @Test
    void usageDefaultsToZeroWhenTheResponseOmitsIt() throws Exception {
        AnthropicProvider provider = new AnthropicProvider(
                request -> "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}");

        LlmProvider.Result result = provider.complete(new LlmProvider.Request(
                "m", "sys", List.of(new LlmProvider.Message("user", "hi")), 100));

        assertEquals("ok", result.text());
        assertEquals(0, result.inputTokens());
        assertEquals(0, result.outputTokens());
    }

    @Test
    void serializeProducesTheAnthropicMessagesBody() {
        String body = AnthropicProvider.serialize(new LlmProvider.Request(
                "claude-x", "be brief",
                List.of(new LlmProvider.Message("user", "earlier"),
                        new LlmProvider.Message("assistant", "noted"),
                        new LlmProvider.Message("user", "now")),
                256));

        assertTrue(body.contains("\"model\":\"claude-x\""));
        assertTrue(body.contains("\"max_tokens\":256"));
        assertTrue(body.contains("\"system\":\"be brief\""));
        assertTrue(body.contains("\"role\":\"assistant\""));
        assertTrue(body.indexOf("earlier") < body.indexOf("now"));
    }

    @Test
    void missingTextBlockThrows() {
        AnthropicProvider provider = new AnthropicProvider(request -> "{\"content\":[]}");
        assertThrows(IllegalStateException.class, () -> provider.parse("{\"content\":[]}"));
    }

    @Test
    void policyCanBeDrivenByAnyProviderThroughWithProvider() {
        // A fake non-Anthropic provider: proves the seam, not the wire format.
        LlmProvider fake = request -> new LlmProvider.Result(
                "answer from " + request.model(), 1, 2);
        AnthropicPolicy policy = AnthropicPolicy.withProvider(fake, "some-model", 100, null);

        Decision decision = policy.decide(AgentContext.initial("hello"));
        assertEquals(new Decision.Respond("answer from some-model"), decision);
    }
}
