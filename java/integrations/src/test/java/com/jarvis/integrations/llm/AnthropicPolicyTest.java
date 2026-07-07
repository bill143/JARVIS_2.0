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
    void recalledMemoryIsInjectedIntoTheSystemPrompt() {
        AnthropicPolicy policy = new AnthropicPolicy(request -> OK_RESPONSE, "m", 100)
                .withMemoryContext(() -> "- prefers metric units\n- working on the go-kart project");
        String request = policy.buildRequest(AgentContext.initial("hi"));
        assertTrue(request.contains("What you remember about the user"));
        assertTrue(request.contains("go-kart project"));
    }

    @Test
    void priorConversationIsIncludedAsMessages() {
        AnthropicPolicy policy = new AnthropicPolicy(request -> OK_RESPONSE, "m", 100)
                .withHistory(() -> java.util.List.of(
                        new AnthropicPolicy.ChatMessage("user", "my name is Bill"),
                        new AnthropicPolicy.ChatMessage("assistant", "Noted, Bill.")));
        String request = policy.buildRequest(AgentContext.initial("what's my name?"));
        assertTrue(request.contains("my name is Bill"));
        assertTrue(request.contains("Noted, Bill."));
        assertTrue(request.contains("what's my name?"));
        // Order: history user, history assistant, then the current user turn.
        assertTrue(request.indexOf("my name is Bill") < request.indexOf("what's my name?"));
    }

    @Test
    void emptyHistoryProducesJustTheCurrentTurn() {
        AnthropicPolicy policy = new AnthropicPolicy(request -> OK_RESPONSE, "m", 100);
        String request = policy.buildRequest(AgentContext.initial("hello"));
        assertTrue(request.contains("hello"));
        assertTrue(request.contains("\"role\":\"user\""));
    }

    @Test
    void personaAddressesTheUserAsSir() {
        AnthropicPolicy policy = new AnthropicPolicy(request -> OK_RESPONSE, "m", 100);
        String request = policy.buildRequest(AgentContext.initial("hello"));
        assertTrue(request.contains("Address the user as 'sir'"));
        assertTrue(request.contains("never mix languages"));
    }

    @Test
    void retryBacksOffExponentiallyThenSucceeds() throws Exception {
        java.util.List<Long> sleeps = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        AnthropicPolicy.LlmTransport flaky = request -> {
            if (calls.incrementAndGet() < 3) {
                throw new java.io.IOException("503 overloaded");
            }
            return OK_RESPONSE;
        };

        AnthropicPolicy.LlmTransport retrying =
                AnthropicPolicy.withRetry(flaky, new long[] {1_000, 2_000, 4_000}, sleeps::add);
        assertEquals(OK_RESPONSE, retrying.complete("{}"));
        assertEquals(3, calls.get());
        assertEquals(java.util.List.of(1_000L, 2_000L), sleeps);
    }

    @Test
    void retryGivesUpAfterAllAttemptsAndThrowsTheLastError() {
        AnthropicPolicy.LlmTransport alwaysDown = request -> {
            throw new java.io.IOException("still down");
        };
        AnthropicPolicy.LlmTransport retrying =
                AnthropicPolicy.withRetry(alwaysDown, new long[] {10, 20}, millis -> { });

        java.io.IOException e = org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class, () -> retrying.complete("{}"));
        assertEquals("still down", e.getMessage());
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
