package com.jarvis.agent.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.agent.loop.AgentPolicy;
import com.jarvis.agent.loop.AgentResult;
import com.jarvis.agent.loop.Decision;
import com.jarvis.agent.routing.PromptRouter;
import com.jarvis.agent.routing.Route;
import com.jarvis.memory.InMemoryStore;
import com.jarvis.memory.MemoryStore;
import com.jarvis.planning.Plan;
import com.jarvis.planning.PlanStep;
import com.jarvis.planning.Planner;
import com.jarvis.planning.StepStatus;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolRegistry;
import com.jarvis.tools.ToolResult;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrchestratorTest {

    private static final AgentPolicy GREETER =
            context -> new Decision.Respond("greeting: " + context.input());
    private static final AgentPolicy GENERAL =
            context -> new Decision.Respond("general: " + context.input());
    /** Invokes a tool every iteration, so it always exhausts the step budget. */
    private static final AgentPolicy NEVER_RESPONDS =
            context -> new Decision.Invoke(new ToolCall("echo", Map.of("text", "again")));

    private static ToolRegistry tools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
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
        });
        return registry;
    }

    private static PromptRouter<AgentPolicy> router() {
        return new PromptRouter<>(List.of(
                new Route<>("greeting",
                        prompt -> prompt.toLowerCase(Locale.ROOT).contains("hello"), GREETER),
                new Route<>("stuck",
                        prompt -> prompt.toLowerCase(Locale.ROOT).contains("stuck"),
                        NEVER_RESPONDS)));
    }

    private static Planner twoStepPlanner() {
        return goal -> new Plan(goal, List.of(
                PlanStep.pending("s1", "hello first"),
                PlanStep.pending("s2", "then anything else")));
    }

    private static Orchestrator orchestrator(MemoryStore<String> memory) {
        return new Orchestrator(router(), GENERAL, tools(), twoStepPlanner(), memory, 3);
    }

    @Test
    void routesPromptToTheMatchingPolicy() {
        AgentResult result = orchestrator(new InMemoryStore<>()).handle("s", "hello there");
        assertEquals("greeting: hello there", result.response());
    }

    @Test
    void unroutedPromptFallsBackToDefaultPolicy() {
        AgentResult result = orchestrator(new InMemoryStore<>()).handle("s", "what time is it");
        assertEquals("general: what time is it", result.response());
    }

    @Test
    void exchangesAreRecordedPerSessionScope() {
        MemoryStore<String> memory = new InMemoryStore<>();
        Orchestrator orchestrator = orchestrator(memory);

        orchestrator.handle("session-a", "hello one");
        orchestrator.handle("session-a", "two");
        orchestrator.handle("session-b", "three");

        assertEquals(2, memory.query("session-a").size());
        assertEquals(1, memory.query("session-b").size());
        String firstTurn = memory.get("session-a", "turn-0").orElseThrow().value();
        assertEquals("hello one -> greeting: hello one", firstTurn);
        assertEquals("RESPONDED",
                memory.get("session-a", "turn-0").orElseThrow().metadata().get("stopReason"));
    }

    @Test
    void budgetExhaustionIsRecordedAsNoResponse() {
        MemoryStore<String> memory = new InMemoryStore<>();
        AgentResult result = orchestrator(memory).handle("s", "I am stuck");

        assertEquals(AgentResult.StopReason.MAX_STEPS_REACHED, result.stopReason());
        String turn = memory.get("s", "turn-0").orElseThrow().value();
        assertTrue(turn.endsWith("-> <no response>"));
        assertEquals("3", memory.get("s", "turn-0").orElseThrow().metadata().get("toolSteps"));
    }

    @Test
    void planRunMarchesEveryStepToCompletion() {
        MemoryStore<String> memory = new InMemoryStore<>();
        PlanRun run = orchestrator(memory).handlePlan("s", "greet then chat");

        assertTrue(run.succeeded());
        assertTrue(run.plan().isComplete());
        assertEquals(2, run.stepResults().size());
        assertEquals("greeting: hello first", run.stepResults().get(0).response());
        assertEquals("general: then anything else", run.stepResults().get(1).response());
        // Each plan step was also recorded as a session turn.
        assertEquals(2, memory.query("s").size());
    }

    @Test
    void failedStepIsMarkedFailedAndExecutionContinues() {
        Planner planner = goal -> new Plan(goal, List.of(
                PlanStep.pending("bad", "I am stuck on this"),
                PlanStep.pending("good", "hello finish")));
        Orchestrator orchestrator = new Orchestrator(
                router(), GENERAL, tools(), planner, new InMemoryStore<>(), 3);

        PlanRun run = orchestrator.handlePlan("s", "goal");
        assertFalse(run.succeeded());
        assertTrue(run.plan().isComplete());
        assertTrue(run.plan().hasFailure());
        assertEquals(StepStatus.FAILED, run.plan().steps().get(0).status());
        assertEquals(StepStatus.COMPLETED, run.plan().steps().get(1).status());
        assertEquals(2, run.stepResults().size());
    }

    @Test
    void emptyPlanSucceedsTrivially() {
        Planner planner = goal -> new Plan(goal, List.of());
        Orchestrator orchestrator = new Orchestrator(
                router(), GENERAL, tools(), planner, new InMemoryStore<>(), 3);

        PlanRun run = orchestrator.handlePlan("s", "nothing");
        assertTrue(run.succeeded());
        assertTrue(run.stepResults().isEmpty());
    }

    @Test
    void constructorAndHandleValidateArguments() {
        MemoryStore<String> memory = new InMemoryStore<>();
        Orchestrator orchestrator = orchestrator(memory);

        assertThrows(NullPointerException.class, () -> orchestrator.handle(null, "p"));
        assertThrows(NullPointerException.class, () -> orchestrator.handle("s", null));
        assertThrows(IllegalArgumentException.class, () -> new Orchestrator(
                router(), GENERAL, tools(), twoStepPlanner(), memory, 0));
        assertThrows(NullPointerException.class, () -> new Orchestrator(
                null, GENERAL, tools(), twoStepPlanner(), memory, 1));
    }
}
