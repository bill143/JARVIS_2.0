package com.jarvis.integrations.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.agent.loop.AgentContext;
import com.jarvis.agent.loop.Decision;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class AnthropicPolicyTest {

    private static final String OK_RESPONSE = """
            {"content":[{"type":"text","text":"Hello Bill!"}],"stop_reason":"end_turn"}""";

    @Test
    void decidesByRespondingWithTheModelText() {
        AnthropicPolicy policy = new AnthropicPolicy(request -> OK_RESPONSE, "test-model", 100);

        Decision decision = policy.decide(AgentContext.initial("say hi"));
        assertEquals(new Decision.Respond("Hello Bill!"), decision);
    }

    @Test
    void requestCarriesModelInputAndLimits() {
        AnthropicPolicy policy = new AnthropicPolicy(request -> OK_RESPONSE, "test-model", 100);

        String request = policy.buildRequest(AgentContext.initial("what's the weather"));
        assertTrue(request.contains("\"model\":\"test-model\""));
        assertTrue(request.contains("\"max_tokens\":100"));
        assertTrue(request.contains("what's the weather"));
        assertTrue(request.contains("\"role\":\"user\""));
        assertTrue(request.contains("JARVIS"));
    }

    @Test
    void transportFailureBecomesAGracefulResponseNotAnException() {
        AnthropicPolicy policy = new AnthropicPolicy(request -> {
            throw new IOException("connection refused");
        }, "test-model", 100);

        Decision decision = policy.decide(AgentContext.initial("hi"));
        Decision.Respond respond = (Decision.Respond) decision;
        assertTrue(respond.message().contains("connection refused"));
    }

    @Test
    void malformedResponseAlsoBecomesAGracefulResponse() {
        AnthropicPolicy policy =
                new AnthropicPolicy(request -> "{\"content\":[]}", "test-model", 100);

        Decision decision = policy.decide(AgentContext.initial("hi"));
        assertTrue(((Decision.Respond) decision).message().startsWith("Sorry"));
    }

    @Test
    void skipsNonTextBlocksWhenExtracting() {
        AnthropicPolicy policy = new AnthropicPolicy(request -> OK_RESPONSE, "m", 10);
        String mixed = """
                {"content":[{"type":"thinking","thinking":"..."},{"type":"text","text":"answer"}]}""";
        assertEquals("answer", policy.extractText(mixed));
    }

    @Test
    void constructorValidation() {
        AnthropicPolicy.LlmTransport transport = request -> OK_RESPONSE;
        assertThrows(NullPointerException.class, () -> new AnthropicPolicy(null, "m", 10));
        assertThrows(NullPointerException.class, () -> new AnthropicPolicy(transport, null, 10));
        assertThrows(IllegalArgumentException.class, () -> new AnthropicPolicy(transport, "m", 0));
        assertThrows(NullPointerException.class,
                () -> AnthropicPolicy.withApiKey(null, "m"));
    }
}
