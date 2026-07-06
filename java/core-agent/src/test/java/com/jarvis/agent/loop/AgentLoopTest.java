package com.jarvis.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolRegistry;
import com.jarvis.tools.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentLoopTest {

    private static Tool echoTool() {
        return new Tool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "echoes the 'text' argument";
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.ok(String.valueOf(call.arguments().get("text")));
            }
        };
    }

    private static ToolRegistry registryWithEcho() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(echoTool());
        return registry;
    }

    @Test
    void policyThatRespondsImmediatelyTakesNoSteps() {
        AgentLoop loop = new AgentLoop(
                context -> new Decision.Respond("hi, " + context.input()),
                registryWithEcho(),
                5);

        AgentResult result = loop.run("bill");
        assertEquals(AgentResult.StopReason.RESPONDED, result.stopReason());
        assertEquals("hi, bill", result.response());
        assertTrue(result.steps().isEmpty());
    }

    @Test
    void toolObservationFeedsNextDecision() {
        // First decision invokes echo; second responds using the observed output.
        AgentPolicy policy = context -> context.steps().isEmpty()
                ? new Decision.Invoke(new ToolCall("echo", Map.of("text", "pong")))
                : new Decision.Respond("tool said: " + context.steps().getLast().result().output());
        AgentLoop loop = new AgentLoop(policy, registryWithEcho(), 5);

        AgentResult result = loop.run("ping");
        assertEquals(AgentResult.StopReason.RESPONDED, result.stopReason());
        assertEquals("tool said: pong", result.response());
        assertEquals(1, result.steps().size());
        assertEquals("echo", result.steps().getFirst().call().toolName());
        assertTrue(result.steps().getFirst().result().success());
    }

    @Test
    void failedToolIsAnObservationNotATermination() {
        AgentPolicy policy = context -> context.steps().isEmpty()
                ? new Decision.Invoke(ToolCall.of("does-not-exist"))
                : new Decision.Respond("saw failure: " + context.steps().getFirst().result().error());
        AgentLoop loop = new AgentLoop(policy, registryWithEcho(), 5);

        AgentResult result = loop.run("x");
        assertEquals(AgentResult.StopReason.RESPONDED, result.stopReason());
        assertTrue(result.response().contains("unknown tool: does-not-exist"));
        assertEquals(1, result.steps().size());
        assertFalse(result.steps().getFirst().result().success());
    }

    @Test
    void stepBudgetStopsAPolicyThatNeverResponds() {
        AgentLoop loop = new AgentLoop(
                context -> new Decision.Invoke(new ToolCall("echo", Map.of("text", "again"))),
                registryWithEcho(),
                3);

        AgentResult result = loop.run("x");
        assertEquals(AgentResult.StopReason.MAX_STEPS_REACHED, result.stopReason());
        assertNull(result.response());
        assertEquals(3, result.steps().size());
    }

    @Test
    void exhaustedBudgetStillAllowsAFinalResponseButNoMoreTools() {
        // Policy wants tools while it can get them, but answers when asked after the budget.
        AgentPolicy policy = context -> context.steps().size() < 3
                ? new Decision.Invoke(new ToolCall("echo", Map.of("text", "s" + context.steps().size())))
                : new Decision.Respond("done after " + context.steps().size() + " steps");
        AgentLoop loop = new AgentLoop(policy, registryWithEcho(), 3);

        AgentResult result = loop.run("x");
        assertEquals(AgentResult.StopReason.RESPONDED, result.stopReason());
        assertEquals("done after 3 steps", result.response());
        assertEquals(3, result.steps().size());
    }

    @Test
    void contextIsImmutableAcrossIterations() {
        AgentContext initial = AgentContext.initial("in");
        AgentContext extended = initial.withStep(
                new AgentStep(ToolCall.of("echo"), ToolResult.ok("out")));

        assertTrue(initial.steps().isEmpty());
        assertEquals(1, extended.steps().size());
        assertThrows(UnsupportedOperationException.class,
                () -> extended.steps().add(new AgentStep(ToolCall.of("echo"), ToolResult.ok("x"))));
    }

    @Test
    void resultInvariantsAreEnforced() {
        assertThrows(NullPointerException.class,
                () -> AgentResult.responded(null, java.util.List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentResult(AgentResult.StopReason.MAX_STEPS_REACHED, "r", java.util.List.of()));
    }

    @Test
    void loopRejectsInvalidConstruction() {
        ToolRegistry registry = registryWithEcho();
        AgentPolicy policy = context -> new Decision.Respond("ok");
        assertThrows(NullPointerException.class, () -> new AgentLoop(null, registry, 1));
        assertThrows(NullPointerException.class, () -> new AgentLoop(policy, null, 1));
        assertThrows(IllegalArgumentException.class, () -> new AgentLoop(policy, registry, 0));
    }

    @Test
    void nullPolicyDecisionFailsFast() {
        AgentLoop loop = new AgentLoop(context -> null, registryWithEcho(), 2);
        assertThrows(NullPointerException.class, () -> loop.run("x"));
    }
}
