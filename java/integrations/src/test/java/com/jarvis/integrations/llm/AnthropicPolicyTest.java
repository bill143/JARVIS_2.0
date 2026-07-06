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

    private static com.jarvis.tools.ToolRegistry echoRegistry() {
        com.jarvis.tools.ToolRegistry registry = new com.jarvis.tools.ToolRegistry();
        registry.register(new com.jarvis.tools.Tool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "echoes the 'text' argument";
            }

            @Override
            public com.jarvis.tools.ToolResult execute(com.jarvis.tools.ToolCall call) {
                return com.jarvis.tools.ToolResult.ok(
                        String.valueOf(call.arguments().get("text")));
            }
        });
        return registry;
    }

    @Test
    void toolAwareRequestAdvertisesTheCatalog() {
        AnthropicPolicy policy =
                new AnthropicPolicy(request -> OK_RESPONSE, "m", 100, echoRegistry());

        String request = policy.buildRequest(AgentContext.initial("hi"));
        assertTrue(request.contains("TOOL:"));
        assertTrue(request.contains("echo: echoes the 'text' argument"));
    }

    @Test
    void toolLineBecomesAnInvokeDecision() {
        String toolReply = """
                {"content":[{"type":"text","text":"TOOL: echo {\\"text\\":\\"pong\\"}"}]}""";
        AnthropicPolicy policy =
                new AnthropicPolicy(request -> toolReply, "m", 100, echoRegistry());

        Decision decision = policy.decide(AgentContext.initial("say pong via the tool"));
        Decision.Invoke invoke = (Decision.Invoke) decision;
        assertEquals("echo", invoke.call().toolName());
        assertEquals("pong", invoke.call().arguments().get("text"));
    }

    @Test
    void withoutARegistryToolLinesAreJustText() {
        String toolReply = """
                {"content":[{"type":"text","text":"TOOL: echo {\\"text\\":\\"pong\\"}"}]}""";
        AnthropicPolicy policy = new AnthropicPolicy(request -> toolReply, "m", 100);

        assertTrue(policy.decide(AgentContext.initial("hi")) instanceof Decision.Respond);
    }

    @Test
    void malformedToolLineFallsBackToRespond() {
        String badJson = """
                {"content":[{"type":"text","text":"TOOL: echo {not json"}]}""";
        AnthropicPolicy policy =
                new AnthropicPolicy(request -> badJson, "m", 100, echoRegistry());

        assertTrue(policy.decide(AgentContext.initial("hi")) instanceof Decision.Respond);
    }

    @Test
    void fullLoopRoundTripThroughAFakeApi() {
        // Turn 1: model asks for the tool. Turn 2 (after seeing the result): model answers.
        AnthropicPolicy.LlmTransport fakeApi = request -> request.contains("[Tool results so far]")
                ? """
                  {"content":[{"type":"text","text":"The tool said: pong"}]}"""
                : """
                  {"content":[{"type":"text","text":"TOOL: echo {\\"text\\":\\"pong\\"}"}]}""";
        com.jarvis.tools.ToolRegistry registry = echoRegistry();
        AnthropicPolicy policy = new AnthropicPolicy(fakeApi, "m", 100, registry);

        com.jarvis.agent.loop.AgentResult result =
                new com.jarvis.agent.loop.AgentLoop(policy, registry, 4).run("use the tool");
        assertEquals(com.jarvis.agent.loop.AgentResult.StopReason.RESPONDED, result.stopReason());
        assertEquals("The tool said: pong", result.response());
        assertEquals(1, result.steps().size());
        assertEquals("pong", result.steps().getFirst().result().output());
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
